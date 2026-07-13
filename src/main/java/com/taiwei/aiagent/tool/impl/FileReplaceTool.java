package com.taiwei.aiagent.tool.impl;

import com.google.gson.Gson;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.taiwei.aiagent.diff.DiffEntry;
import com.taiwei.aiagent.diff.DiffReviewService;
import com.taiwei.aiagent.tool.Tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 精确替换文件内容工具
 * 支持三种模式：str_replace（文本替换）、line_replace（行范围替换）、insert_after（行后插入）
 */
public class FileReplaceTool implements Tool {

    private final Project project;
    private final Gson gson = new Gson();

    public FileReplaceTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "file_replace";
    }

    @Override
    public String getDescription() {
        return "精确替换文件中的指定文本。三种模式：str_replace（按文本内容替换）、line_replace（按行号范围替换）、insert_after（在指定行后插入）。"
                + "str_replace 当 old_string 仅出现一次时直接替换；出现多次时需设置 replace_all=true。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "file_path": {
                      "type": "string",
                      "description": "文件路径（绝对路径或相对项目根目录的路径）"
                    },
                    "mode": {
                      "type": "string",
                      "enum": ["str_replace", "line_replace", "insert_after"],
                      "description": "操作模式，默认 str_replace"
                    },
                    "old_string": {
                      "type": "string",
                      "description": "[str_replace] 要被替换的旧文本"
                    },
                    "new_string": {
                      "type": "string",
                      "description": "替换后的新文本（传空字符串表示删除匹配内容）"
                    },
                    "replace_all": {
                      "type": "boolean",
                      "description": "[str_replace] 是否替换所有匹配项，默认 false"
                    },
                    "start_line": {
                      "type": "integer",
                      "description": "[line_replace] 起始行号（1-based）"
                    },
                    "end_line": {
                      "type": "integer",
                      "description": "[line_replace] 结束行号（1-based，包含该行）"
                    },
                    "insert_line": {
                      "type": "integer",
                      "description": "[insert_after] 在此行之后插入内容"
                    }
                  },
                  "required": ["file_path"]
                }
                """;
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    @Override
    public String execute(String arguments) {
        try {
            ReplaceArgs args = gson.fromJson(arguments, ReplaceArgs.class);
            if (args.file_path == null) {
                return "错误: 缺少必要参数 file_path";
            }

            ExecuteContext ctx = prepareExecute(args.file_path);
            String mode = args.mode != null ? args.mode : "str_replace";

            return switch (mode) {
                case "line_replace" -> executeLineReplace(ctx.resolved, ctx.oldContent, args);
                case "insert_after" -> executeInsertAfter(ctx.resolved, ctx.oldContent, args);
                default -> executeStrReplace(ctx.resolved, ctx.oldContent, args);
            };

        } catch (Exception e) {
            return "文件替换失败: " + e.getMessage();
        }
    }

    private String executeStrReplace(Path resolved, String oldContent, ReplaceArgs args) throws Exception {
        if (args.old_string == null || args.old_string.isEmpty()) {
            return "错误: str_replace 模式需要非空的 old_string";
        }

        int count = countOccurrences(oldContent, args.old_string);

        if (count == 0) {
            return "错误: 未找到匹配内容 - " + truncate(args.old_string, 80);
        }

        String newContent;
        String replacement = args.new_string == null ? "" : args.new_string;
        if (count == 1) {
            newContent = replaceFirst(oldContent, args.old_string, replacement);
        } else if (!args.replace_all) {
            return "错误: 找到 " + count + " 处匹配，请确认是否使用 replace_all=true 批量替换";
        } else {
            newContent = oldContent.replace(args.old_string, replacement);
        }

        writeAndRecordDiff(resolved, oldContent, newContent);

        int replacedCount = args.replace_all ? count : 1;
        return "替换成功: 共替换 " + replacedCount + " 处 - " + resolved;
    }

    private String executeLineReplace(Path resolved, String oldContent, ReplaceArgs args) throws Exception {
        if (args.start_line == null || args.end_line == null) {
            return "错误: line_replace 模式需要 start_line 和 end_line 参数";
        }

        String[] lines = oldContent.split("\n", -1);
        int totalLines = lines.length;

        if (args.start_line < 1 || args.start_line > totalLines) {
            return "错误: start_line 超出范围 [1, " + totalLines + "]，当前值: " + args.start_line;
        }
        if (args.end_line < 1 || args.end_line > totalLines) {
            return "错误: end_line 超出范围 [1, " + totalLines + "]，当前值: " + args.end_line;
        }
        if (args.start_line > args.end_line) {
            return "错误: start_line (" + args.start_line + ") 不能大于 end_line (" + args.end_line + ")";
        }

        // Bug 3: stripTrailing new_string to avoid trailing newlines causing blank lines
        String newString = args.new_string == null ? "" : args.new_string.stripTrailing();

        // Bug 1: Three-stage construction — no in-loop conditionals on replacement logic
        StringBuilder sb = new StringBuilder();

        // 范围前的行
        for (int i = 0; i < args.start_line - 1; i++) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(lines[i]);
        }

        // 替换内容（之前补换行）
        if (sb.length() > 0) sb.append("\n");
        sb.append(newString);

        // 范围后的行
        for (int i = args.end_line; i < lines.length; i++) {
            sb.append("\n");
            sb.append(lines[i]);
        }

        String newContent = sb.toString();
        writeAndRecordDiff(resolved, oldContent, newContent);

        return "替换成功: 已替换第 " + args.start_line + "~" + args.end_line + " 行 - " + resolved;
    }

    private String executeInsertAfter(Path resolved, String oldContent, ReplaceArgs args) throws Exception {
        if (args.insert_line == null) {
            return "错误: insert_after 模式需要 insert_line 参数";
        }

        String[] lines = oldContent.split("\n", -1);
        int totalLines = lines.length;

        if (args.insert_line < 0 || args.insert_line > totalLines) {
            return "错误: insert_line 超出范围 [0, " + totalLines + "]，当前值: " + args.insert_line;
        }

        String newString = args.new_string == null ? "" : args.new_string;

        String newContent;
        // Bug 2: insert_line == 0 时插在文件最前面
        if (args.insert_line == 0) {
            newContent = newString + "\n" + oldContent;
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.insert_line; i++) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(lines[i]);
            }
            sb.append("\n").append(newString);
            for (int i = args.insert_line; i < lines.length; i++) {
                sb.append("\n");
                sb.append(lines[i]);
            }
            newContent = sb.toString();
        }

        writeAndRecordDiff(resolved, oldContent, newContent);

        return "插入成功: 已在第 " + args.insert_line + " 行后插入内容 - " + resolved;
    }

    private ExecuteContext prepareExecute(String filePath) throws Exception {
        Path resolved = resolvePath(filePath);
        if (!Files.exists(resolved)) {
            throw new Exception("文件不存在 - " + resolved);
        }
        if (Files.isDirectory(resolved)) {
            throw new Exception("路径是目录而非文件 - " + resolved);
        }
        String oldContent = Files.readString(resolved, StandardCharsets.UTF_8);
        return new ExecuteContext(resolved, oldContent);
    }

    private void writeAndRecordDiff(Path resolved, String oldContent, String newContent) throws Exception {
        Files.writeString(resolved, newContent, StandardCharsets.UTF_8);

        DiffEntry diffEntry = new DiffEntry(resolved.toString(), oldContent, newContent);
        DiffReviewService.getInstance(project).addDiff(diffEntry);

        ApplicationManager.getApplication().invokeAndWait(() -> {
            VirtualFile vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(resolved.toFile());
            if (vFile != null) {
                Document document = FileDocumentManager.getInstance().getDocument(vFile);
                if (document != null) {
                    FileDocumentManager.getInstance().reloadFromDisk(document);
                }
            }
        });
    }

    private int countOccurrences(String content, String target) {
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }

    private String replaceFirst(String content, String target, String replacement) {
        int idx = content.indexOf(target);
        if (idx == -1) {
            return content;
        }
        return content.substring(0, idx) + replacement + content.substring(idx + target.length());
    }

    private String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    private Path resolvePath(String filePath) {
        Path path = Paths.get(filePath);
        if (path.isAbsolute()) {
            return path;
        }
        String basePath = project.getBasePath();
        if (basePath != null) {
            return Paths.get(basePath, filePath);
        }
        return path;
    }

    private static class ExecuteContext {
        final Path resolved;
        final String oldContent;

        ExecuteContext(Path resolved, String oldContent) {
            this.resolved = resolved;
            this.oldContent = oldContent;
        }
    }

    private static class ReplaceArgs {
        String file_path;
        String old_string;
        String new_string;
        boolean replace_all;
        String mode;
        Integer start_line;
        Integer end_line;
        Integer insert_line;
    }
}

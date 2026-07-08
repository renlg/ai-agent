package com.taiwei.aiagent.tool.impl;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
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
 * Agent 可通过此工具对文件中的指定文本做精确替换，避免重写整个文件
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
        return "精确替换文件中的指定文本。适用于修改少量代码、版本号、配置项等小范围修改，"
                + "优先使用此工具而非 write_file 重写整个文件。"
                + "当 old_string 在文件中仅出现一次时直接替换；出现多次时需显式设置 replace_all=true 才会批量替换。";
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
                    "old_string": {
                      "type": "string",
                      "description": "要被替换的旧文本"
                    },
                    "new_string": {
                      "type": "string",
                      "description": "替换后的新文本（传空字符串表示删除匹配内容）"
                    },
                    "replace_all": {
                      "type": "boolean",
                      "description": "是否替换所有匹配项，默认 false。为 false 时若存在多处匹配将返回错误"
                    }
                  },
                  "required": ["file_path", "old_string", "new_string"]
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        try {
            ReplaceArgs args = gson.fromJson(arguments, ReplaceArgs.class);
            if (args.file_path == null || args.old_string == null) {
                return "错误: 缺少必要参数 file_path 或 old_string";
            }
            if (args.old_string.isEmpty()) {
                return "错误: old_string 不能为空";
            }

            Path resolved = resolvePath(args.file_path);
            if (!Files.exists(resolved)) {
                return "错误: 文件不存在 - " + resolved;
            }
            if (Files.isDirectory(resolved)) {
                return "错误: 路径是目录而非文件 - " + resolved;
            }

            String oldContent = Files.readString(resolved, StandardCharsets.UTF_8);
            int count = countOccurrences(oldContent, args.old_string);

            if (count == 0) {
                return "错误: 未找到匹配内容 - " + truncate(args.old_string, 80);
            }

            String newContent;
            if (count == 1) {
                newContent = replaceFirst(oldContent, args.old_string, args.new_string == null ? "" : args.new_string);
            } else if (!args.replace_all) {
                return "错误: 找到 " + count + " 处匹配，请确认是否使用 replace_all=true 批量替换";
            } else {
                newContent = oldContent.replace(args.old_string, args.new_string == null ? "" : args.new_string);
            }

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

            int replacedCount = args.replace_all ? count : 1;
            return "替换成功: 共替换 " + replacedCount + " 处 - " + resolved;

        } catch (Exception e) {
            return "文件替换失败: " + e.getMessage();
        }
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

    private static class ReplaceArgs {
        @SerializedName("file_path")
        String file_path;
        @SerializedName("old_string")
        String old_string;
        @SerializedName("new_string")
        String new_string;
        @SerializedName("replace_all")
        boolean replace_all;
    }
}

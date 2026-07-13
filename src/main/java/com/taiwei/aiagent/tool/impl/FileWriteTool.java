package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.vfs.VirtualFile;
import com.taiwei.aiagent.diff.DiffEntry;
import com.taiwei.aiagent.diff.DiffReviewService;
import com.taiwei.aiagent.tool.Tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 写入/编辑文件工具
 * Agent 可以通过此工具创建新文件或修改已有文件
 */
public class FileWriteTool implements Tool {

    private final Project project;

    public FileWriteTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "write_file";
    }

    @Override
    public String getDescription() {
        return "将内容写入指定路径的文件。如果文件不存在则创建，如果存在则覆盖。支持创建目录结构。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "文件路径（绝对路径或相对项目根目录的路径）"
                    },
                    "content": {
                      "type": "string",
                      "description": "要写入的文件内容"
                    }
                  },
                  "required": ["path", "content"]
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
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String filePath = args.get("path").getAsString();
            String content = args.get("content").getAsString();

            Path resolved = resolvePath(filePath);

            // 确保父目录存在
            Path parent = resolved.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            // 读取旧内容（用于后续 diff 比较）
            final String oldContent;
            if (Files.exists(resolved)) {
                oldContent = Files.readString(resolved, StandardCharsets.UTF_8);
            } else {
                oldContent = "";
            }

            // 写入文件
            Files.writeString(resolved, content, StandardCharsets.UTF_8);

            // 写入文件后同步记录 diff
            DiffEntry diffEntry = new DiffEntry(resolved.toString(), oldContent, content);
            DiffReviewService.getInstance(project).addDiff(diffEntry);

            // 同步刷新 VFS，并强制编辑器重新加载（保证 IDEA 编辑器内容实时刷新）
            // 必须在 EDT 线程执行，因为 VFS API 要求 EDT 访问
            ApplicationManager.getApplication().invokeAndWait(() -> {
                VirtualFile vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(resolved.toFile());
                if (vFile != null) {
                    Document document = FileDocumentManager.getInstance().getDocument(vFile);
                    if (document != null) {
                        FileDocumentManager.getInstance().reloadFromDisk(document);
                    }
                }
            });

            return "文件写入成功: " + resolved;

        } catch (Exception e) {
            return "写入文件失败: " + e.getMessage();
        }
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
}

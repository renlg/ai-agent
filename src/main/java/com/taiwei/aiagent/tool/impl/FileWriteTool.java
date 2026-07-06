package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.taiwei.aiagent.tool.Tool;

import java.io.IOException;
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

            // 写入文件
            Files.writeString(resolved, content, StandardCharsets.UTF_8);

            // 刷新 VFS 让 IDE 感知到文件变化
            ApplicationManager.getApplication().invokeLater(() -> {
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(resolved.toFile());
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

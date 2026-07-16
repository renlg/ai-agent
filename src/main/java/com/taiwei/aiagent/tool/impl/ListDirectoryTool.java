package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.tool.Tool;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 列出目录结构工具
 * Agent 可以通过此工具探索项目目录布局，避免盲猜文件路径
 */
public class ListDirectoryTool implements Tool {

    private static final int DEFAULT_MAX_DEPTH = 3;
    private static final int MAX_MAX_DEPTH = 8;
    private static final int MAX_ENTRIES = 500;

    /** 跳过的目录名（构建产物、依赖、IDE 元数据等） */
    private static final Set<String> SKIP_DIRS = new HashSet<>(Arrays.asList(
            ".git", ".idea", ".gradle", "build", "out", "dist", "target",
            "node_modules", "__pycache__", ".venv", "venv", ".taiwei-ide-sandbox"
    ));

    private final Project project;

    public ListDirectoryTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "list_directory";
    }

    @Override
    public String getDescription() {
        return "列出指定目录的文件和子目录结构（树形文本）。用于探索项目布局、确认文件路径。"
                + "默认从项目根目录开始，深度 3 层。会自动跳过 .git、build、node_modules 等目录。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "要列出的目录路径（绝对路径或相对项目根目录的路径，默认为项目根目录）"
                    },
                    "max_depth": {
                      "type": "integer",
                      "description": "递归深度（默认 3，最大 8）"
                    }
                  }
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        try {
            JsonObject args = arguments == null || arguments.isBlank()
                    ? new JsonObject()
                    : JsonParser.parseString(arguments).getAsJsonObject();
            String dirPath = args.has("path") && !args.get("path").isJsonNull()
                    ? args.get("path").getAsString() : ".";
            int maxDepth = args.has("max_depth") ? args.get("max_depth").getAsInt() : DEFAULT_MAX_DEPTH;
            maxDepth = Math.max(1, Math.min(maxDepth, MAX_MAX_DEPTH));

            Path resolved = resolvePath(dirPath);
            if (!Files.exists(resolved)) {
                return "错误: 目录不存在 - " + resolved;
            }
            if (!Files.isDirectory(resolved)) {
                return "错误: 路径不是目录 - " + resolved;
            }

            StringBuilder sb = new StringBuilder();
            sb.append(resolved).append("/\n");
            int[] count = {0};
            listRecursive(resolved, "", 1, maxDepth, sb, count);
            if (count[0] >= MAX_ENTRIES) {
                sb.append("\n... [条目过多，已截断至 ").append(MAX_ENTRIES).append(" 条，请指定更深层的子目录查看]");
            }
            return sb.toString();

        } catch (Exception e) {
            return "列出目录失败: " + e.getMessage();
        }
    }

    private void listRecursive(Path dir, String indent, int depth, int maxDepth,
                               StringBuilder sb, int[] count) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                entries.add(entry);
            }
        }
        // 目录在前，其余按名称排序
        entries.sort(Comparator
                .comparing((Path p) -> !Files.isDirectory(p))
                .thenComparing(p -> p.getFileName().toString()));

        for (Path entry : entries) {
            if (count[0] >= MAX_ENTRIES) {
                return;
            }
            String name = entry.getFileName().toString();
            boolean isDir = Files.isDirectory(entry);
            if (isDir && (SKIP_DIRS.contains(name) || name.startsWith("."))) {
                continue;
            }
            count[0]++;
            if (isDir) {
                sb.append(indent).append(name).append("/\n");
                if (depth < maxDepth) {
                    listRecursive(entry, indent + "  ", depth + 1, maxDepth, sb, count);
                }
            } else {
                sb.append(indent).append(name).append("\n");
            }
        }
    }

    private Path resolvePath(String dirPath) {
        Path path = Paths.get(dirPath);
        if (path.isAbsolute()) {
            return path;
        }
        String basePath = project.getBasePath();
        if (basePath != null) {
            return Paths.get(basePath, dirPath).normalize();
        }
        return path;
    }
}

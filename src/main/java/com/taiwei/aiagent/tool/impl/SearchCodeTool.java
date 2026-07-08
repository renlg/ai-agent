package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.taiwei.aiagent.tool.Tool;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 搜索代码工具
 * Agent 可以通过此工具在项目中按关键词或正则搜索代码
 */
public class SearchCodeTool implements Tool {

    private final Project project;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public SearchCodeTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "search_code";
    }

    @Override
    public String getDescription() {
        return "在项目文件中进行代码文本搜索。支持正则表达式和关键词搜索，可按文件名过滤。推荐策略：先使用 find_symbol（符号级搜索）精确定位类/方法/字段，再使用本工具进行文本级搜索补充。如果搜索不到结果，尝试使用更简洁的关键词或正则片段。支持 Java、Kotlin、XML、Properties、YAML 等文本文件。返回结构化 JSON，包含文件路径、行号、预览行。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "query": {
                      "type": "string",
                      "description": "搜索关键词或正则表达式"
                    },
                    "file_pattern": {
                      "type": "string",
                      "description": "文件名过滤（glob 模式，如 '*.java'、'*.xml'，可选）"
                    },
                    "max_results": {
                      "type": "integer",
                      "description": "最大返回结果数（默认 50）"
                    }
                  },
                  "required": ["query"]
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String query = args.get("query").getAsString();
            String filePattern = args.has("file_pattern") ? args.get("file_pattern").getAsString() : null;
            int maxResults = args.has("max_results") ? args.get("max_results").getAsInt() : 50;

            Pattern pattern;
            try {
                pattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                // 如果不是有效正则，按字面量搜索
                pattern = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
            }

            final Pattern searchPattern = pattern;
            return ReadAction.compute(() -> {
                VirtualFile baseDir = project.getBaseDir();
                if (baseDir == null) {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("error", "无法获取项目根目录");
                    return gson.toJson(error);
                }

                List<Map<String, Object>> results = new ArrayList<>();
                searchInDirectory(baseDir, searchPattern, filePattern, maxResults, results);

                Map<String, Object> output = new LinkedHashMap<>();
                output.put("query", query);
                output.put("total", results.size());
                output.put("results", results);

                return gson.toJson(output);
            });

        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "搜索失败: " + e.getMessage());
            return gson.toJson(error);
        }
    }

    private void searchInDirectory(VirtualFile dir, Pattern pattern, String filePattern,
                                   int maxResults, List<Map<String, Object>> results) {
        VfsUtilCore.visitChildrenRecursively(dir, new VirtualFileVisitor<Void>() {
            @Override
            public boolean visitFile(VirtualFile file) {
                if (results.size() >= maxResults) {
                    return false;
                }

                // 跳过隐藏目录和构建目录
                if (file.isDirectory()) {
                    String name = file.getName();
                    return !name.startsWith(".") && !name.equals("build") && !name.equals("node_modules");
                }

                // 文件名过滤
                if (filePattern != null && !matchGlob(file.getName(), filePattern)) {
                    return false;
                }

                // 跳过二进制文件和大文件
                if (file.getLength() > 1_000_000) {
                    return false;
                }

                try {
                    String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
                    String[] lines = content.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        if (results.size() >= maxResults) break;
                        Matcher matcher = pattern.matcher(lines[i]);
                        if (matcher.find()) {
                            String relativePath = VfsUtilCore.getRelativePath(file, project.getBaseDir());
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("file", relativePath);
                            entry.put("line", i + 1);
                            entry.put("symbol_type", "text_match");
                            entry.put("signature", "");
                            entry.put("containing_class", "");
                            // 上下文预览行
                            List<String> preview = new ArrayList<>();
                            int start = Math.max(0, i - 2);
                            int end = Math.min(lines.length, i + 3);
                            for (int j = start; j < end; j++) {
                                preview.add((j == i ? ">>> " : "    ") + lines[j].trim());
                            }
                            entry.put("preview_lines", preview);
                            results.add(entry);
                        }
                    }
                } catch (Exception ignored) {
                    // 跳过无法读取的文件
                }

                return true;
            }
        });
    }

    /**
     * 简单的 glob 匹配（支持 * 和 ?）
     */
    private boolean matchGlob(String fileName, String glob) {
        String regex = glob
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return fileName.matches(regex);
    }
}

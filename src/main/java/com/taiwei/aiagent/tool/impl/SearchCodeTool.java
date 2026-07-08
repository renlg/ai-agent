package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.taiwei.aiagent.tool.Tool;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 搜索代码工具
 * Agent 可以通过此工具在项目中按关键词或正则搜索代码
 */
public class SearchCodeTool implements Tool {

    private final Project project;

    public SearchCodeTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "search_code";
    }

    @Override
    public String getDescription() {
        return "在项目文件中搜索包含指定关键词或正则匹配的代码行，返回匹配的文件路径和行号。";
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
                    return "错误: 无法获取项目根目录";
                }

                List<String> results = new ArrayList<>();
                searchInDirectory(baseDir, searchPattern, filePattern, maxResults, results);

                if (results.isEmpty()) {
                    return "未找到匹配结果";
                }

                return String.join("\n", results);
            });

        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }
    }

    private void searchInDirectory(VirtualFile dir, Pattern pattern, String filePattern,
                                   int maxResults, List<String> results) {
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
                            results.add(String.format("%s:%d: %s", relativePath, i + 1, lines[i].trim()));
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

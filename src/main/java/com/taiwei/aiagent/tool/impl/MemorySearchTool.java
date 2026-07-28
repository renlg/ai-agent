package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.memory.MemoryManager;
import com.taiwei.aiagent.tool.Tool;

/**
 * 记忆搜索工具
 * 混合检索（关键词 + 向量语义 RRF 融合）已保存的长期记忆
 */
public class MemorySearchTool implements Tool {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final Project project;

    public MemorySearchTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "search_memory";
    }

    @Override
    public String getDescription() {
        return "搜索已保存的记忆（地址/位置/偏好/事实等），用户询问个人信息时立即调用";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "query": {
                      "type": "string",
                      "description": "搜索内容，如 \\"用户喜欢的饮料\\""
                    },
                    "category": {
                      "type": "string",
                      "enum": ["fact", "preference", "context", "command"],
                      "description": "记忆分类过滤（可选）：fact-事实，preference-偏好，context-上下文，command-常用命令"
                    },
                    "limit": {
                      "type": "integer",
                      "description": "最多返回条数（可选，默认 5，最大 20）"
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
            if (!args.has("query")) {
                return "错误: 缺少必填参数 query";
            }
            String query = args.get("query").getAsString().trim();
            if (query.isEmpty()) {
                return "错误: query 不能为空";
            }
            String category = args.has("category") ? args.get("category").getAsString() : null;
            int limit = args.has("limit") ? args.get("limit").getAsInt() : DEFAULT_LIMIT;
            limit = Math.max(1, Math.min(limit, MAX_LIMIT));

            String result = MemoryManager.getInstance(project).hybridSearch(query, category, limit);
            return result.isBlank() ? "未找到相关记忆" : result;

        } catch (Exception e) {
            return "搜索记忆失败: " + e.getMessage();
        }
    }

    @Override
    public boolean isMutating() {
        return false;
    }
}

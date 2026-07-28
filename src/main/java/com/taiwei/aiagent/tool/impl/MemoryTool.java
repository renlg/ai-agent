package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.memory.MemoryCategory;
import com.taiwei.aiagent.memory.MemoryManager;
import com.taiwei.aiagent.tool.Tool;

import java.util.List;
import java.util.Locale;

/**
 * 记忆保存工具
 * 用户要求"记住"某个事实时，Agent 通过此工具将其持久化到长期记忆
 */
public class MemoryTool implements Tool {

    private final Project project;

    public MemoryTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "save_memory";
    }

    @Override
    public String getDescription() {
        return "保存一条长期记忆。当用户要求记住某个事实、偏好或信息时调用（如\"记住我喜欢喝冰美式\"）。"
                + "记忆会持久化存储，并在后续相关对话中自动召回。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "key": {
                      "type": "string",
                      "description": "记忆的简短标识，如 \\"favorite_drink\\""
                    },
                    "content": {
                      "type": "string",
                      "description": "要记住的内容，如 \\"用户喜欢喝冰美式\\""
                    },
                    "category": {
                      "type": "string",
                      "enum": ["fact", "preference", "context", "command"],
                      "description": "记忆分类（可选，默认 fact）：fact-事实，preference-偏好，context-上下文，command-常用命令"
                    }
                  },
                  "required": ["key", "content"]
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            if (!args.has("key") || !args.has("content")) {
                return "错误: 缺少必填参数 key 或 content";
            }
            String key = args.get("key").getAsString().trim();
            String content = args.get("content").getAsString().trim();
            if (key.isEmpty() || content.isEmpty()) {
                return "错误: key 和 content 不能为空";
            }

            MemoryCategory category = parseCategory(
                    args.has("category") ? args.get("category").getAsString() : null);

            MemoryManager.getInstance(project).remember(content, category, List.of(key), 5);
            return "已记住: " + key + " = " + content;

        } catch (Exception e) {
            return "保存记忆失败: " + e.getMessage();
        }
    }

    private MemoryCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return MemoryCategory.FACT;
        }
        try {
            return MemoryCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MemoryCategory.FACT;
        }
    }

    @Override
    public boolean isMutating() {
        return true;
    }
}

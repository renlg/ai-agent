package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.taiwei.aiagent.tool.Tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量任务/计划追踪工具
 * 在 Agent 会话内以内存 Map 维护一个有序任务列表，支持增删改查
 */
public class TodoPlanTool implements Tool {

    private final List<String> items = new ArrayList<>();
    // index (0-based) -> completed
    private final Map<Integer, Boolean> completed = new HashMap<>();
    private String planName = "";

    @Override
    public String getName() {
        return "todo_plan";
    }

    @Override
    public String getDescription() {
        return "轻量任务/计划追踪工具。在 Agent 会话内跨调用保持状态。"
                + "操作：init（初始化计划并可选批量添加项目）、add（追加新项目）、"
                + "complete（标记某项目完成）、status（查看当前状态和进度）。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "action": {
                      "type": "string",
                      "enum": ["init", "add", "complete", "status"],
                      "description": "操作类型"
                    },
                    "plan_name": {
                      "type": "string",
                      "description": "[init] 计划名称"
                    },
                    "items": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "[init / add] 要添加的项目列表"
                    },
                    "item_index": {
                      "type": "integer",
                      "description": "[complete] 要标记完成的项目索引（1-based）"
                    }
                  },
                  "required": ["action"]
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String action = args.get("action").getAsString();

            return switch (action) {
                case "init"     -> doInit(args);
                case "add"      -> doAdd(args);
                case "complete" -> doComplete(args);
                case "status"   -> doStatus();
                default -> "错误: 未知操作 \"" + action + "\"，支持的操作: init, add, complete, status";
            };
        } catch (Exception e) {
            return "todo_plan 执行失败: " + e.getMessage();
        }
    }

    private synchronized String doInit(JsonObject args) {
        planName = args.has("plan_name") ? args.get("plan_name").getAsString() : "未命名计划";
        items.clear();
        completed.clear();

        if (args.has("items")) {
            JsonArray arr = args.get("items").getAsJsonArray();
            for (var elem : arr) {
                items.add(elem.getAsString());
            }
        }

        return "计划 \"" + planName + "\" 已初始化，共 " + items.size() + " 项";
    }

    private synchronized String doAdd(JsonObject args) {
        if (!args.has("items")) return "错误: add 操作需要 items 参数";

        JsonArray arr = args.get("items").getAsJsonArray();
        int added = 0;
        for (var elem : arr) {
            items.add(elem.getAsString());
            added++;
        }
        return "已添加 " + added + " 个项目，当前共 " + items.size() + " 项";
    }

    private synchronized String doComplete(JsonObject args) {
        if (!args.has("item_index")) return "错误: complete 操作需要 item_index 参数";

        int idx = args.get("item_index").getAsInt();
        if (idx < 1 || idx > items.size()) {
            return "错误: item_index " + idx + " 超出范围 [1, " + items.size() + "]";
        }

        completed.put(idx - 1, true);
        return "项目 #" + idx + " 已标记完成: " + items.get(idx - 1);
    }

    private synchronized String doStatus() {
        if (items.isEmpty()) {
            return "当前无计划。使用 init 操作创建计划。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("计划: ").append(planName).append("\n\n");

        int doneCount = 0;
        for (int i = 0; i < items.size(); i++) {
            boolean done = completed.getOrDefault(i, false);
            if (done) doneCount++;
            sb.append(done ? "[x]" : "[ ]").append(" ").append(i + 1).append(". ").append(items.get(i)).append("\n");
        }

        sb.append("\n进度: ").append(doneCount).append("/").append(items.size())
          .append("  (待完成: ").append(items.size() - doneCount).append(")");
        return sb.toString();
    }
}

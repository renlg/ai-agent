package com.taiwei.aiagent.tool;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心
 * 管理所有 Agent 可调用的工具实例
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * 注册工具
     */
    public void register(Tool tool) {
        if (tool == null || tool.getName() == null) {
            throw new IllegalArgumentException("工具及其名称不能为空");
        }
        tools.put(tool.getName(), tool);
    }

    /**
     * 注销工具
     */
    public void unregister(String toolName) {
        tools.remove(toolName);
    }

    /**
     * 根据名称获取工具
     */
    public Tool getTool(String name) {
        return tools.get(name);
    }

    /**
     * 获取所有已注册工具列表
     */
    public List<Tool> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    /**
     * 检查工具是否已注册
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * 获取已注册工具数量
     */
    public int size() {
        return tools.size();
    }
}

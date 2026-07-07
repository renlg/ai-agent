package com.taiwei.aiagent.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 会话模型
 * 管理一次对话的完整消息历史
 */
public class Conversation {

    /**
     * 会话唯一 ID
     */
    private final String id;

    /**
     * 消息历史列表
     */
    private final List<ChatMessage> messages;

    /**
     * 系统提示词
     */
    private String systemPrompt;

    /**
     * 会话标题（自动从第一条用户消息生成）
     */
    private String title;

    /**
     * 会话创建时间戳
     */
    private final long createdAt;

    public Conversation() {
        this.id = UUID.randomUUID().toString();
        this.messages = new ArrayList<>();
        this.createdAt = System.currentTimeMillis();
    }

    public Conversation(String systemPrompt) {
        this();
        this.systemPrompt = systemPrompt;
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(ChatMessage.system(systemPrompt));
        }
    }

    /**
     * 添加用户消息
     */
    public void addUserMessage(String content) {
        messages.add(ChatMessage.user(content));
        if (title == null && content != null && !content.isEmpty()) {
            title = content.length() > 30 ? content.substring(0, 30) + "..." : content;
        }
    }

    /**
     * 添加助手消息
     */
    public void addAssistantMessage(String content) {
        messages.add(ChatMessage.assistant(content));
    }

    /**
     * 添加工具调用结果
     */
    public void addToolResult(String content, String toolCallId) {
        messages.add(ChatMessage.tool(content, toolCallId));
    }

    /**
     * 添加带工具调用的助手消息
     */
    public void addAssistantToolCalls(ChatMessage.ToolCall[] toolCalls, String content) {
        ChatMessage msg = new ChatMessage("assistant", content);
        msg.setToolCalls(toolCalls);
        messages.add(msg);
    }

    /**
     * 获取消息列表（用于发送给 LLM）
     */
    public List<ChatMessage> getMessages() {
        return new ArrayList<>(messages);
    }

    /**
     * 清空消息历史（保留系统提示词）
     */
    public void clear() {
        messages.clear();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(ChatMessage.system(systemPrompt));
        }
    }

    /**
     * 获取最后一条助手消息
     */
    public ChatMessage getLastAssistantMessage() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if ("assistant".equals(msg.getRole())) {
                return msg;
            }
        }
        return null;
    }

    // ========== Getters ==========

    public String getId() {
        return id;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public int getMessageCount() {
        return messages.size();
    }
}

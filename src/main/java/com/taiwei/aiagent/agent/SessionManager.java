package com.taiwei.aiagent.agent;

import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 多会话管理器
 * 管理多个 AgentContext 实例，每个实例对应一个独立的会话
 */
public class SessionManager {

    private final Project project;
    private final LinkedHashMap<String, AgentContext> sessions = new LinkedHashMap<>();
    private String activeSessionId;

    public SessionManager(Project project) {
        this.project = project;
    }

    /**
     * 创建新会话并设为活跃会话
     *
     * @return 新会话的 sessionId
     */
    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        AgentContext context = new AgentContext(project);
        sessions.put(sessionId, context);
        activeSessionId = sessionId;
        return sessionId;
    }

    /**
     * 获取或创建会话
     * 如果 sessionId 对应的会话已存在，返回该会话；否则创建新会话
     *
     * @param sessionId 会话 ID，为 null 或空则创建新会话
     * @return 会话 ID
     */
    public String getOrCreateSession(String sessionId) {
        if (sessionId != null && !sessionId.isEmpty() && sessions.containsKey(sessionId)) {
            activeSessionId = sessionId;
            return sessionId;
        }
        return createSession();
    }

    /**
     * 切换到指定会话
     */
    public void switchSession(String sessionId) {
        if (sessions.containsKey(sessionId)) {
            activeSessionId = sessionId;
        }
    }

    /**
     * 获取当前活跃会话的上下文
     */
    public AgentContext getActiveContext() {
        if (activeSessionId == null) {
            createSession();
        }
        return sessions.get(activeSessionId);
    }

    /**
     * 获取指定会话的上下文
     */
    public AgentContext getContext(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 获取当前活跃会话 ID
     */
    public String getActiveSessionId() {
        if (activeSessionId == null) {
            createSession();
        }
        return activeSessionId;
    }

    /**
     * 列出所有会话的摘要信息
     */
    public List<SessionInfo> listSessions() {
        List<SessionInfo> list = new ArrayList<>();
        for (Map.Entry<String, AgentContext> entry : sessions.entrySet()) {
            String id = entry.getKey();
            AgentContext ctx = entry.getValue();
            String title = ctx.getConversation().getTitle();
            long createdAt = ctx.getConversation().getCreatedAt();
            int messageCount = ctx.getConversation().getMessageCount();
            boolean active = id.equals(activeSessionId);
            list.add(new SessionInfo(id, title, createdAt, messageCount, active));
        }
        return list;
    }

    /**
     * 删除指定会话
     * 如果删除的是当前活跃会话，自动切换到最近的会话
     */
    public void deleteSession(String sessionId) {
        sessions.remove(sessionId);
        if (sessionId.equals(activeSessionId)) {
            if (sessions.isEmpty()) {
                createSession();
            } else {
                String lastKey = null;
                for (String key : sessions.keySet()) {
                    lastKey = key;
                }
                activeSessionId = lastKey;
            }
        }
    }

    /**
     * 会话摘要信息
     */
    public static class SessionInfo {
        private final String id;
        private final String title;
        private final long createdAt;
        private final int messageCount;
        private final boolean active;

        public SessionInfo(String id, String title, long createdAt, int messageCount, boolean active) {
            this.id = id;
            this.title = title;
            this.createdAt = createdAt;
            this.messageCount = messageCount;
            this.active = active;
        }

        public String getId() { return id; }
        public String getTitle() { return title; }
        public long getCreatedAt() { return createdAt; }
        public int getMessageCount() { return messageCount; }
        public boolean isActive() { return active; }
    }
}

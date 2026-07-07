package com.taiwei.aiagent.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话磁盘持久化存储
 * 将最多 5 个最新会话以 JSON 格式保存到项目目录/.taiwei
 */
public class SessionStore {

    private static final Logger LOG = Logger.getInstance(SessionStore.class);
    private static final int MAX_SESSIONS = 5;
    private static final String STORE_FILE = ".taiwei";

    private final Path filePath;
    private final Gson gson;

    public SessionStore(String basePath) {
        this.filePath = Paths.get(basePath, STORE_FILE);
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    /**
     * 保存会话列表到磁盘（最多保留 MAX_SESSIONS 个，超出时丢弃最旧的）
     */
    public void save(List<SessionData> sessions) {
        try {
            List<SessionData> toSave;
            if (sessions.size() > MAX_SESSIONS) {
                toSave = new ArrayList<>(sessions.subList(sessions.size() - MAX_SESSIONS, sessions.size()));
            } else {
                toSave = new ArrayList<>(sessions);
            }

            File parentDir = filePath.toFile().getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            String json = gson.toJson(toSave);
            Files.write(filePath, json.getBytes(StandardCharsets.UTF_8));
            LOG.info("已保存 " + toSave.size() + " 个会话到 " + filePath);
        } catch (IOException e) {
            LOG.warn("保存会话失败失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从磁盘加载会话列表
     */
    public List<SessionData> load() {
        if (!Files.exists(filePath)) {
            LOG.info("会话存储文件不存在，返回空列表");
            return Collections.emptyList();
        }

        try {
            String json = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
            Type type = new TypeToken<List<SessionData>>() {}.getType();
            List<SessionData> sessions = gson.fromJson(json, type);
            LOG.info("从 " + filePath + " 加载了 " + (sessions != null ? sessions.size() : 0) + " 个会话");
            return sessions != null ? sessions : Collections.emptyList();
        } catch (IOException e) {
            LOG.warn("加载会话失败: " + e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 删除指定会话并持久化（若存储文件存在）
     */
    public void delete(String sessionId) {
        List<SessionData> sessions = load();
        sessions.removeIf(s -> sessionId.equals(s.id));
        save(sessions);
    }

    /**
     * 删除存储文件
     */
    public void deleteStoreFile() {
        try {
            Files.deleteIfExists(filePath);
            LOG.info("已删除会话存储文件: " + filePath);
        } catch (IOException e) {
            LOG.warn("删除存储文件失败: " + e.getMessage(), e);
        }
    }

    // ========== 数据模型 ==========

    public static class SessionData {
        public String id;
        public String title;
        public long createdAt;
        public List<MessageData> messages;

        public SessionData() {}

        public SessionData(String id, String title, long createdAt, List<MessageData> messages) {
            this.id = id;
            this.title = title;
            this.createdAt = createdAt;
            this.messages = messages;
        }
    }

    public static class MessageData {
        public String role;
        public String content;
        public String toolName;
        public String toolArgs;
        public String toolResult;
        public String toolCallId;
        public List<ToolCallData> toolCalls;

        public MessageData() {}

        public MessageData(String role, String content, String toolName, String toolArgs,
                           String toolResult, String toolCallId, List<ToolCallData> toolCalls) {
            this.role = role;
            this.content = content;
            this.toolName = toolName;
            this.toolArgs = toolArgs;
            this.toolResult = toolResult;
            this.toolCallId = toolCallId;
            this.toolCalls = toolCalls;
        }
    }

    public static class ToolCallData {
        public String id;
        public String type;
        public FunctionCallData function;

        public ToolCallData() {}

        public ToolCallData(String id, String type, FunctionCallData function) {
            this.id = id;
            this.type = type;
            this.function = function;
        }
    }

    public static class FunctionCallData {
        public String name;
        public String arguments;

        public FunctionCallData() {}

        public FunctionCallData(String name, String arguments) {
            this.name = name;
            this.arguments = arguments;
        }
    }
}

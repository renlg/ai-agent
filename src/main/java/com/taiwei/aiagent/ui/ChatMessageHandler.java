package com.taiwei.aiagent.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.taiwei.aiagent.agent.AgentService;
import com.taiwei.aiagent.agent.SessionManager;
import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.settings.AiAgentSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * JS ↔ Java 桥接处理器
 * 处理前端页面发来的消息，调用 Agent 服务，并将结果回传给前端
 */
public class ChatMessageHandler {

    private static final Logger LOG = Logger.getInstance(ChatMessageHandler.class);

    private final Project project;
    private final JBCefBrowser browser;
    private final AgentService agentService;

    // JS → Java 查询通道
    private final JBCefJSQuery sendMessageQuery;
    private final JBCefJSQuery clearChatQuery;
    private final JBCefJSQuery getModelsQuery;
    private final JBCefJSQuery switchModelQuery;
    private final JBCefJSQuery createSessionQuery;
    private final JBCefJSQuery listSessionsQuery;
    private final JBCefJSQuery switchSessionQuery;
    private final JBCefJSQuery deleteSessionQuery;
    private final JBCefJSQuery getSessionHistoryQuery;

    public ChatMessageHandler(@NotNull Project project, @NotNull JBCefBrowser browser) {
        this.project = project;
        this.browser = browser;
        this.agentService = new AgentService(project);

        // 创建 JS → Java 查询通道
        @SuppressWarnings("removal")
        JBCefJSQuery sendQ = JBCefJSQuery.create(browser);
        this.sendMessageQuery = sendQ;
        @SuppressWarnings("removal")
        JBCefJSQuery clearQ = JBCefJSQuery.create(browser);
        this.clearChatQuery = clearQ;
        @SuppressWarnings("removal")
        JBCefJSQuery getModelsQ = JBCefJSQuery.create(browser);
        this.getModelsQuery = getModelsQ;
        @SuppressWarnings("removal")
        JBCefJSQuery switchModelQ = JBCefJSQuery.create(browser);
        this.switchModelQuery = switchModelQ;
        @SuppressWarnings("removal")
        JBCefJSQuery createSessionQ = JBCefJSQuery.create(browser);
        this.createSessionQuery = createSessionQ;
        @SuppressWarnings("removal")
        JBCefJSQuery listSessionsQ = JBCefJSQuery.create(browser);
        this.listSessionsQuery = listSessionsQ;
        @SuppressWarnings("removal")
        JBCefJSQuery switchSessionQ = JBCefJSQuery.create(browser);
        this.switchSessionQuery = switchSessionQ;
        @SuppressWarnings("removal")
        JBCefJSQuery deleteSessionQ = JBCefJSQuery.create(browser);
        this.deleteSessionQuery = deleteSessionQ;
        @SuppressWarnings("removal")
        JBCefJSQuery getSessionHistoryQ = JBCefJSQuery.create(browser);
        this.getSessionHistoryQuery = getSessionHistoryQ;

        setupHandlers();
        setupLoadHandler();
    }

    /**
     * 设置消息处理逻辑
     */
    private void setupHandlers() {
        // 处理发送消息（支持 JSON 格式：{sessionId, message} 或纯文本）
        sendMessageQuery.addHandler(jsMessage -> {
            LOG.info("收到前端消息: " + jsMessage);

            String sessionId = null;
            String message = jsMessage;

            // 尝试解析为 JSON（带 sessionId）
            try {
                JsonObject json = JsonParser.parseString(jsMessage).getAsJsonObject();
                if (json.has("sessionId") && json.has("message")) {
                    sessionId = json.get("sessionId").getAsString();
                    message = json.get("message").getAsString();
                }
            } catch (Exception e) {
                // 不是 JSON，按纯文本处理
            }

            final String finalSessionId = sessionId;
            final String finalMessage = message;

            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                agentService.sendMessage(finalSessionId, finalMessage, new AgentService.AgentListener() {
                    @Override
                    public void onThinking() {
                        executeJs("onThinking()");
                    }

                    @Override
                    public void onContent(String content) {
                        String escaped = escapeJs(content);
                        executeJs("onContent('" + escaped + "')");
                    }

                    @Override
                    public void onToolCallStart(String toolName, String arguments) {
                        String escaped = escapeJs(arguments);
                        executeJs("onToolCallStart('" + toolName + "', '" + escaped + "')");
                    }

                    @Override
                    public void onToolCallEnd(String toolName, String result) {
                        String escaped = escapeJs(result);
                        executeJs("onToolCallEnd('" + toolName + "', '" + escaped + "')");
                    }

                    @Override
                    public void onComplete(String fullResponse) {
                        String escaped = escapeJs(fullResponse);
                        executeJs("onComplete('" + escaped + "')");
                    }

                    @Override
                    public void onError(String error) {
                        String escaped = escapeJs(error);
                        executeJs("onError('" + escaped + "')");
                    }
                });
            });

            return new JBCefJSQuery.Response("ok");
        });

        // 处理清空对话
        clearChatQuery.addHandler(jsMessage -> {
            agentService.resetConversation();
            return new JBCefJSQuery.Response("ok");
        });

        // 处理获取模型列表
        getModelsQuery.addHandler(jsMessage -> {
            AiAgentSettings settings = AiAgentSettings.getInstance();
            List<AiAgentSettings.ModelConfig> configs = settings.getModelConfigs();
            int activeIndex = settings.getActiveModelIndex();

            LOG.info("getModels 被调用，模型数量: " + configs.size() + ", 活跃索引: " + activeIndex);

            JsonArray modelsArray = new JsonArray();
            for (int i = 0; i < configs.size(); i++) {
                AiAgentSettings.ModelConfig config = configs.get(i);
                JsonObject obj = new JsonObject();
                obj.addProperty("index", i);
                String displayName = config.name.isEmpty() ? config.modelName : config.name;
                obj.addProperty("name", displayName);
                obj.addProperty("model", config.modelName);
                obj.addProperty("active", i == activeIndex);
                modelsArray.add(obj);
                LOG.info("模型[" + i + "]: " + displayName + " (" + config.modelName + ")");
            }

            JsonObject result = new JsonObject();
            result.add("models", modelsArray);
            result.addProperty("activeIndex", activeIndex);

            String responseStr = result.toString();
            LOG.info("getModels 返回: " + responseStr);
            return new JBCefJSQuery.Response(responseStr);
        });

        // 处理切换模型
        switchModelQuery.addHandler(jsMessage -> {
            try {
                int modelIndex = Integer.parseInt(jsMessage.trim());
                AiAgentSettings settings = AiAgentSettings.getInstance();

                int beforeIndex = settings.getActiveModelIndex();
                AiAgentSettings.ModelConfig beforeConfig = settings.getActiveModelConfig();
                LOG.info("切换前: activeIndex=" + beforeIndex + ", baseUrl=" + beforeConfig.baseUrl + ", model=" + beforeConfig.modelName);

                settings.setActiveModelIndex(modelIndex);
                agentService.getContext().switchModel(modelIndex);

                int afterIndex = settings.getActiveModelIndex();
                AiAgentSettings.ModelConfig afterConfig = settings.getActiveModelConfig();
                String displayName = afterConfig.name.isEmpty() ? afterConfig.modelName : afterConfig.name;
                LOG.info("切换后: activeIndex=" + afterIndex + ", baseUrl=" + afterConfig.baseUrl + ", model=" + afterConfig.modelName);

                return new JBCefJSQuery.Response("{\"ok\":true,\"name\":\"" + escapeJs(displayName) + "\"}");
            } catch (Exception e) {
                LOG.warn("切换模型失败", e);
                return new JBCefJSQuery.Response("{\"ok\":false,\"error\":\"" + escapeJs(e.getMessage()) + "\"}");
            }
        });

        // 处理创建新会话
        createSessionQuery.addHandler(jsMessage -> {
            String sessionId = agentService.createSession();
            LOG.info("创建新会话: " + sessionId);
            return new JBCefJSQuery.Response("{\"sessionId\":\"" + sessionId + "\"}");
        });

        // 处理列出所有会话
        listSessionsQuery.addHandler(jsMessage -> {
            List<SessionManager.SessionInfo> sessions = agentService.listSessions();
            String activeId = agentService.getActiveSessionId();

            JsonArray sessionsArray = new JsonArray();
            for (SessionManager.SessionInfo info : sessions) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", info.getId());
                obj.addProperty("title", info.getTitle() != null ? info.getTitle() : "新会话");
                obj.addProperty("createdAt", info.getCreatedAt());
                obj.addProperty("messageCount", info.getMessageCount());
                obj.addProperty("active", info.getId().equals(activeId));
                sessionsArray.add(obj);
            }

            JsonObject result = new JsonObject();
            result.add("sessions", sessionsArray);
            result.addProperty("activeSessionId", activeId);

            return new JBCefJSQuery.Response(result.toString());
        });

        // 处理切换会话
        switchSessionQuery.addHandler(jsMessage -> {
            try {
                String sessionId = jsMessage.trim();
                agentService.switchSession(sessionId);
                LOG.info("切换到会话: " + sessionId);
                return new JBCefJSQuery.Response("{\"ok\":true,\"sessionId\":\"" + sessionId + "\"}");
            } catch (Exception e) {
                LOG.warn("切换会话失败", e);
                return new JBCefJSQuery.Response("{\"ok\":false,\"error\":\"" + escapeJs(e.getMessage()) + "\"}");
            }
        });

        // 处理删除会话
        deleteSessionQuery.addHandler(jsMessage -> {
            try {
                String sessionId = jsMessage.trim();
                agentService.deleteSession(sessionId);
                String newActiveId = agentService.getActiveSessionId();
                LOG.info("删除会话: " + sessionId + ", 新活跃会话: " + newActiveId);
                return new JBCefJSQuery.Response("{\"ok\":true,\"newActiveSessionId\":\"" + newActiveId + "\"}");
            } catch (Exception e) {
                LOG.warn("删除会话失败", e);
                return new JBCefJSQuery.Response("{\"ok\":false,\"error\":\"" + escapeJs(e.getMessage()) + "\"}");
            }
        });

        // 处理获取会话历史消息
        getSessionHistoryQuery.addHandler(jsMessage -> {
            try {
                String sessionId = jsMessage.trim();
                SessionManager sm = agentService.getSessionManager();
                var context = sm.getContext(sessionId);

                JsonArray messagesArray = new JsonArray();
                if (context != null) {
                    List<ChatMessage> messages = context.getConversation().getMessages();
                    for (ChatMessage msg : messages) {
                        String role = msg.getRole();
                        if ("system".equals(role)) continue;

                        JsonObject obj = new JsonObject();
                        obj.addProperty("role", role);
                        obj.addProperty("content", msg.getContent() != null ? msg.getContent() : "");
                        messagesArray.add(obj);
                    }
                }

                JsonObject result = new JsonObject();
                result.add("messages", messagesArray);
                result.addProperty("sessionId", sessionId);
                return new JBCefJSQuery.Response(result.toString());
            } catch (Exception e) {
                LOG.warn("获取会话历史失败", e);
                return new JBCefJSQuery.Response("{\"messages\":[],\"error\":\"" + escapeJs(e.getMessage()) + "\"}");
            }
        });
    }

    /**
     * 页面加载完成后注入 JS 桥接代码
     */
    private void setupLoadHandler() {
        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                String jsBridge = buildJsBridge();
                frame.executeJavaScript(jsBridge, frame.getURL(), 0);
                LOG.info("JS Bridge 注入完成");
            }
        }, browser.getCefBrowser());
    }

    /**
     * 构建注入到前端的 JS 桥接代码
     */
    private String buildJsBridge() {
        String sendJs = sendMessageQuery.inject("message");
        String clearJs = clearChatQuery.inject("''");
        String getModelsJs = getModelsQuery.inject("''");
        String switchModelJs = switchModelQuery.inject("modelIndex");
        String createSessionJs = createSessionQuery.inject("''");
        String listSessionsJs = listSessionsQuery.inject("''");
        String switchSessionJs = switchSessionQuery.inject("sessionId");
        String deleteSessionJs = deleteSessionQuery.inject("sessionId");
        String getSessionHistoryJs = getSessionHistoryQuery.inject("sessionId");

        return """
                // 太微 JS Bridge
                window.aiAgent = {
                    sendMessage: function(message) {
                        %s
                    },
                    
                    clearChat: function() {
                        %s
                    },
                    
                    getModels: async function(callback) {
                        try {
                            var resp = await %s;
                            if (callback) callback(resp);
                        } catch(e) { console.error('getModels error:', e); }
                    },
                    
                    switchModel: async function(modelIndex, callback) {
                        try {
                            var resp = await %s;
                            if (callback) callback(resp);
                        } catch(e) { console.error('switchModel error:', e); }
                    },
                    
                    createSession: async function(callback) {
                        try {
                            var resp = await %s;
                            if (callback) callback(resp);
                        } catch(e) { console.error('createSession error:', e); }
                    },
                    
                    listSessions: async function(callback) {
                        try {
                            var resp = await %s;
                            if (callback) callback(resp);
                        } catch(e) { console.error('listSessions error:', e); }
                    },
                    
                    switchSession: async function(sessionId, callback) {
                        try {
                            var resp = await %s;
                            if (callback) callback(resp);
                        } catch(e) { console.error('switchSession error:', e); }
                    },
                    
                    deleteSession: async function(sessionId, callback) {
                        try {
                            var resp = await %s;
                            if (callback) callback(resp);
                        } catch(e) { console.error('deleteSession error:', e); }
                    },
                    
                    getSessionHistory: async function(sessionId, callback) {
                        try {
                            var resp = await %s;
                            if (callback) callback(resp);
                        } catch(e) { console.error('getSessionHistory error:', e); }
                    }
                };
                
                if (typeof onBridgeReady === 'function') {
                    onBridgeReady();
                }
                console.log('太微 Bridge 已加载');
                """.formatted(sendJs, clearJs, getModelsJs, switchModelJs,
                createSessionJs, listSessionsJs, switchSessionJs, deleteSessionJs, getSessionHistoryJs);
    }

    /**
     * 在浏览器中执行 JavaScript
     */
    private void executeJs(String js) {
        ApplicationManager.getApplication().invokeLater(() -> {
            browser.getCefBrowser().executeJavaScript(js, browser.getCefBrowser().getURL(), 0);
        });
    }

    /**
     * 转义 JS 字符串中的特殊字符
     */
    private String escapeJs(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("'", "\\'")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    public AgentService getAgentService() {
        return agentService;
    }

    public void dispose() {
        sendMessageQuery.dispose();
        clearChatQuery.dispose();
        getModelsQuery.dispose();
        switchModelQuery.dispose();
        createSessionQuery.dispose();
        listSessionsQuery.dispose();
        switchSessionQuery.dispose();
        deleteSessionQuery.dispose();
        getSessionHistoryQuery.dispose();
    }
}

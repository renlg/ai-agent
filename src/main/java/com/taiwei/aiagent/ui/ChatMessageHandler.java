package com.taiwei.aiagent.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.taiwei.aiagent.agent.AgentService;
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

        setupHandlers();
        setupLoadHandler();
    }

    /**
     * 设置消息处理逻辑
     */
    private void setupHandlers() {
        // 处理发送消息
        sendMessageQuery.addHandler(jsMessage -> {
            LOG.info("收到前端消息: " + jsMessage);

            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                agentService.sendMessage(jsMessage, new AgentService.AgentListener() {
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
                    }
                };
                
                if (typeof onBridgeReady === 'function') {
                    onBridgeReady();
                }
                console.log('太微 Bridge 已加载');
                """.formatted(sendJs, clearJs, getModelsJs, switchModelJs);
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
    }
}

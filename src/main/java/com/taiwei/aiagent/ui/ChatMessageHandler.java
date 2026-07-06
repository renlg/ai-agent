package com.taiwei.aiagent.ui;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.taiwei.aiagent.agent.AgentService;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.NotNull;

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

            // 在后台线程执行 Agent 调用（避免阻塞 UI）
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                agentService.sendMessage(jsMessage, new AgentService.AgentListener() {
                    @Override
                    public void onThinking() {
                        executeJs("onThinking()");
                    }

                    @Override
                    public void onContent(String content) {
                        // 转义特殊字符后传给前端
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
    }

    /**
     * 页面加载完成后注入 JS 桥接代码
     */
    private void setupLoadHandler() {
        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                // 注入 JS 桥接函数
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

        return """
                // 太微 JS Bridge
                window.aiAgent = {
                    // 发送消息给 Agent
                    sendMessage: function(message) {
                        %s
                    },
                    
                    // 清空对话
                    clearChat: function() {
                        %s
                    }
                };
                
                // 通知前端页面已就绪
                if (typeof onBridgeReady === 'function') {
                    onBridgeReady();
                }
                console.log('太微 Bridge 已加载');
                """.formatted(sendJs, clearJs);
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
    }
}

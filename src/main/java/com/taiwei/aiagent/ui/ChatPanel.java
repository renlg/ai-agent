package com.taiwei.aiagent.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.taiwei.aiagent.agent.AgentContext;
import com.taiwei.aiagent.agent.AgentService;
import com.taiwei.aiagent.agent.SessionManager;
import com.taiwei.aiagent.llm.LlmResponse;
import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.settings.AiAgentSettings;

import org.cef.browser.CefBrowser;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ChatPanel extends JPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(ChatPanel.class);

    private final Project project;
    private final AgentService agentService;

    private JBCefBrowser browser;
    private JBCefJSQuery jsQuery;

    private final Map<String, SessionState> sessionStates = new HashMap<>();
    private final Map<String, PendingCommand> pendingCommands = new HashMap<>();
    private final Runnable settingsChangeListener = this::onSettingsChanged;

    public ChatPanel(Project project) {
        this.project = project;
        this.agentService = new AgentService(project);
        AiAgentSettings.getInstance().addChangeListener(settingsChangeListener);
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout());
        initBrowser();
        add(browser.getComponent(), BorderLayout.CENTER);
        pushInitialData();
    }

    private void initBrowser() {
        browser = new JBCefBrowser();
        setupJsBridge();

        String theme = JBColor.isBright() ? "light" : "dark";
        String html = buildHtmlContent(theme);
        browser.loadHTML(html);

        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadingStateChange(CefBrowser cefBrowser,
                                             boolean isLoading,
                                             boolean canGoBack,
                                             boolean canGoForward) {
                if (!isLoading) {
                    pushInitialData();
                }
            }
        }, browser.getCefBrowser());
    }

    private void setupJsBridge() {
        jsQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        jsQuery.addHandler(request -> {
            handleJsMessage(request);
            return new JBCefJSQuery.Response("ok");
        });
    }

    // ========== HTML Content Assembly ==========

    private String buildHtmlContent(String theme) {
        String html = loadResource("/html/chat.html");
        String css = loadResource("/html/css/chat.css");
        String markdownJs = loadResource("/html/js/markdown.js");
        String chatJs = loadResource("/html/js/chat.js");

        String injection = "window.taiweiQuery = function(msg) { " +
                jsQuery.inject("msg") + " };";

        html = html.replace("__CSS__", css);
        html = html.replace("__MARKDOWN_JS__", markdownJs);
        html = html.replace("__CHAT_JS__", injection + "\n" + chatJs);
        html = html.replace("__THEME__", theme);
        return html;
    }

    private static String loadResource(String path) {
        try (InputStream is = ChatPanel.class.getResourceAsStream(path)) {
            if (is == null) return "";
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            LOG.warn("Failed to load resource: " + path, e);
            return "";
        }
    }

    // ========== Push initial data to JS ==========

    private void pushInitialData() {
        pushSessionListToJs();
        pushModelListToJs();
        pushHistoryToJs();
    }

    private void pushSessionListToJs() {
        List<SessionManager.SessionInfo> sessions = agentService.listSessions();
        String activeId = agentService.getActiveSessionId();

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < sessions.size(); i++) {
            if (i > 0) json.append(",");
            SessionManager.SessionInfo info = sessions.get(i);
            String title = info.getTitle() != null && !info.getTitle().isEmpty()
                    ? info.getTitle() : "\u65b0\u4f1a\u8bdd";
            json.append("{\"id\":")
                    .append(escapeJsString(info.getId()))
                    .append(",\"title\":")
                    .append(escapeJsString(title))
                    .append(",\"active\":")
                    .append(info.getId().equals(activeId))
                    .append("}");
        }
        json.append("]");

        String js = "if(typeof updateSessionList==='function'){updateSessionList(" + json + "," + escapeJsString(activeId) + ");}";
        CefBrowser cef = browser.getCefBrowser();
        if (cef != null) {
            cef.executeJavaScript(js, "taiwei-push", 0);
        }
    }

    private void pushModelListToJs() {
        List<AiAgentSettings.ModelConfig> configs =
                AiAgentSettings.getInstance().getModelConfigs();
        int activeIndex = AiAgentSettings.getInstance().getActiveModelIndex();

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < configs.size(); i++) {
            if (i > 0) json.append(",");
            AiAgentSettings.ModelConfig config = configs.get(i);
            String displayName = config.name.isEmpty() ? config.modelName : config.name;
            json.append("{\"name\":")
                    .append(escapeJsString(displayName))
                    .append(",\"active\":")
                    .append(i == activeIndex)
                    .append("}");
        }
        json.append("]");

        String js = "if(typeof updateModelList==='function'){updateModelList(" + json + "," + activeIndex + ");}";
        CefBrowser cef = browser.getCefBrowser();
        if (cef != null) {
            cef.executeJavaScript(js, "taiwei-push", 0);
        }
    }

    // ========== JS → Java Message Handling ==========

    private void handleJsMessage(String request) {
        try {
            com.google.gson.JsonObject json =
                    com.google.gson.JsonParser.parseString(request).getAsJsonObject();
            String action = json.get("action").getAsString();
            com.google.gson.JsonObject data = json.has("data")
                    ? json.getAsJsonObject("data") : new com.google.gson.JsonObject();

            switch (action) {
                case "sendMessage":
                    String content = data.has("content") ? data.get("content").getAsString() : "";
                    sendMessage(content);
                    break;
                case "createSession":
                    createNewSession();
                    break;
                case "switchSession":
                    switchToSession(data.get("sessionId").getAsString());
                    break;
                case "deleteSession":
                    deleteSession(data.get("sessionId").getAsString());
                    break;
                case "clearChat":
                    clearChat();
                    break;
                case "selectModel":
                    int modelIndex = data.get("index").getAsInt();
                    AiAgentSettings.getInstance().setActiveModelIndex(modelIndex);
                    break;
                case "getSessions":
                    pushSessionListToJs();
                    break;
                case "getModels":
                    pushModelListToJs();
                    break;
                case "runCommand":
                    // 用户点击了危险命令的运行按钮
                    String toolCallId = data.get("toolCallId").getAsString();
                    onUserApproveCommand(toolCallId);
                    break;
                case "stopGeneration":
                    stopGeneration();
                    break;
                default:
                    LOG.warn("Unknown JS action: " + action);
            }
        } catch (Exception e) {
            LOG.error("Error handling JS message: " + request, e);
        }
    }

    // ========== Java → JS Push ==========

    private void pushToJs(String func, String data) {
        Runnable task = () -> {
            String js = "if(typeof " + func + "==='function'){" + func + "(" + data + ");}";
            CefBrowser cef = browser.getCefBrowser();
            if (cef != null) {
                cef.executeJavaScript(js, "taiwei-push", 0);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    private void pushContent(String content) {
        pushToJs("appendContent", escapeJsString(content));
    }

    private void pushToolCallStart(String name, String args) {
        return;
    }

    private void pushToolCallEnd(String name, String result) {
        return;
    }

    private void pushComplete() {
        pushToJs("onComplete", "");
    }

    private void pushError(String error) {
        pushToJs("onError", escapeJsString(error));
    }

    private void pushUsageToJs(LlmResponse.Usage usage, long elapsedMs) {
        String json = "{\"usage\":{\"promptTokens\":" + usage.getPromptTokens()
                + ",\"completionTokens\":" + usage.getCompletionTokens()
                + ",\"totalTokens\":" + usage.getTotalTokens() + "}"
                + ",\"elapsedMs\":" + elapsedMs + "}";
        pushToJs("updateTokenUsage", json);
    }

    private void pushHistoryToJs() {
        AgentContext ctx = agentService.getContext();
        if (ctx == null) return;

        List<ChatMessage> messages = ctx.getConversation().getMessages();
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (ChatMessage msg : messages) {
            String role = msg.getRole();
            if ("user".equals(role) || ("assistant".equals(role)
                    && msg.getContent() != null && !msg.getContent().isEmpty())) {
                if (!first) json.append(",");
                json.append("{\"role\":")
                        .append(escapeJsString(role))
                        .append(",\"content\":")
                        .append(escapeJsString(msg.getContent()))
                        .append("}");
                first = false;
            }
        }

        // 如果当前会话正在处理中，追加流式内容到前端显示
        String activeId = agentService.getActiveSessionId();
        SessionState state = sessionStates.get(activeId);
        boolean isActiveProcessing = state != null && state.isProcessing;
        if (isActiveProcessing && state.accumulatedContent.length() > 0) {
            if (!first) json.append(",");
            json.append("{\"role\":\"assistant\",\"content\":")
                    .append(escapeJsString(state.accumulatedContent.toString()))
                    .append("}");
        }

        json.append("]");
        pushToJs("loadHistory", escapeJsString(json.toString()) + "," + isActiveProcessing);
    }

    private static String escapeJsString(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<':  sb.append("\\u003c"); break;
                case '>':  sb.append("\\u003e"); break;
                case '&':  sb.append("\\u0026"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append("\"").toString();
    }

    // ========== Pending Command Management ==========

    /**
     * 待审批的命令
     */
    private static class PendingCommand {
        final String toolCallId;
        final String command;
        final boolean isDangerous;

        PendingCommand(String toolCallId, String command, boolean isDangerous) {
            this.toolCallId = toolCallId;
            this.command = command;
            this.isDangerous = isDangerous;
        }
    }

    /**
     * JS 端点击运行按钮后触发
     * 仅发送审批信号，实际命令执行由 AgentService 通过 RunCommandTool 在终端中完成
     */
    private void onUserApproveCommand(String toolCallId) {
        PendingCommand pc = pendingCommands.remove(toolCallId);
        if (pc == null) {
            LOG.warn("未找到待审批的命令: " + toolCallId);
            return;
        }

        // 隐藏运行按钮，显示进度
        pushToJs("hideRunButton", escapeJsString(toolCallId));
        pushToJs("showProgress",
                escapeJsString(toolCallId) + "," + escapeJsString("命令已批准，在终端执行中..."));

        // 发送审批通过信号，AgentService 收到后会自动通过 RunCommandTool 在终端执行
        agentService.getApprovalManager().setResult(toolCallId, "__APPROVED__");
    }

    // ========== Stop Generation ==========

    private void stopGeneration() {
        agentService.stopGeneration();
    }

    // ========== Send Message ==========

    private void sendMessage(String text) {
        if (text == null || text.trim().isEmpty()) return;

        String sessionId = agentService.getActiveSessionId();
        if (sessionId == null) return;

        SessionState sessionState = sessionStates.computeIfAbsent(sessionId, k -> new SessionState());
        if (sessionState.isProcessing) return;

        sessionState.isProcessing = true;
        sessionState.accumulatedContent = new StringBuilder();
        sessionState.startTime = System.currentTimeMillis();
        sessionState.lastUsage = null;
        sessionState.chatEntries.add(ChatEntry.user(text));

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            agentService.sendMessage(sessionId, text, new AgentService.AgentListener() {

                @Override
                public void onThinking() {
                }

                @Override
                public void onContent(String content) {
                    sessionState.accumulatedContent.append(content);

                    boolean replaced = false;
                    for (int i = sessionState.chatEntries.size() - 1; i >= 0; i--) {
                        if (sessionState.chatEntries.get(i).type == ChatEntry.Type.THINKING) {
                            sessionState.chatEntries.set(i, ChatEntry.assistant(sessionState.accumulatedContent.toString()));
                            replaced = true;
                            break;
                        }
                    }
                    if (!replaced) {
                        for (int i = sessionState.chatEntries.size() - 1; i >= 0; i--) {
                            if (sessionState.chatEntries.get(i).type == ChatEntry.Type.ASSISTANT) {
                                sessionState.chatEntries.get(i).content = sessionState.accumulatedContent.toString();
                                break;
                            }
                        }
                    }

                    // 仅当当前活跃会话是本 listener 对应的会话时才推送到前端
                    if (sessionId.equals(agentService.getActiveSessionId())) {
                        pushContent(content);
                    }
                }

                @Override
                public void onToolCallStart(String toolCallId, String toolName, String arguments) {
                    sessionState.chatEntries.add(ChatEntry.toolCall(toolCallId, toolName, arguments));

                    if (sessionId.equals(agentService.getActiveSessionId())) {
                        pushToJs("showProgress",
                                escapeJsString(toolCallId) + "," + escapeJsString("执行 " + toolName + "..."));
                    }
                }

                @Override
                public void onToolCallEnd(String toolCallId, String toolName, String result) {
                    for (int i = sessionState.chatEntries.size() - 1; i >= 0; i--) {
                        ChatEntry entry = sessionState.chatEntries.get(i);
                        if (entry.type == ChatEntry.Type.TOOL_CALL
                                && toolCallId.equals(entry.toolCallId)
                                && entry.toolResult == null) {
                            entry.toolResult = result;
                            break;
                        }
                    }

                    pushToJs("showProgress",
                            escapeJsString(toolCallId) + "," + escapeJsString("\u2705 \u5b8c\u6210"));
                    // 延迟隐藏进度条，让用户看到完成状态
                    ApplicationManager.getApplication().invokeLater(() -> {
                        try {
                            Thread.sleep(300); // 短暂显示完成状态
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        pushToJs("hideProgress", escapeJsString(toolCallId));
                    });
                }

                @Override
                public void onCommandApproval(String toolCallId, String command, boolean isDangerous) {
                    sessionState.chatEntries.add(ChatEntry.toolCall(toolCallId, "run_command", command));

                    if (!sessionId.equals(agentService.getActiveSessionId())) return;

                    if (isDangerous) {
                        // 危险命令：显示运行按钮，等待用户点击
                        pendingCommands.put(toolCallId, new PendingCommand(toolCallId, command, true));
                        pushToJs("showRunButton",
                                escapeJsString(toolCallId) + "," + escapeJsString(command));
                    } else {
                        // 安全命令：显示进度条，直接执行
                        pushToJs("showProgress",
                                escapeJsString(toolCallId) + "," + escapeJsString("执行中..."));
                    }
                }

                @Override
                public void onCommandProgress(String toolCallId, String status) {
                    if (sessionId.equals(agentService.getActiveSessionId())) {
                        pushToJs("showProgress",
                                escapeJsString(toolCallId) + "," + escapeJsString(status));
                    }
                }

                @Override
                public void onCommandResult(String toolCallId, String result) {
                    PendingCommand pc = pendingCommands.remove(toolCallId);
                    boolean isDangerous = pc != null;

                    if (isDangerous) {
                        pushToJs("hideRunButton", escapeJsString(toolCallId));
                    } else {
                        pushToJs("showProgress",
                                escapeJsString(toolCallId) + "," + escapeJsString("\u2705 \u5b8c\u6210"));
                        pushToJs("hideRunButton", escapeJsString(toolCallId));
                        // 延迟隐藏进度条，让用户看到完成状态
                        ApplicationManager.getApplication().invokeLater(() -> {
                            try {
                                Thread.sleep(300); // 短暂显示完成状态
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            pushToJs("hideProgress", escapeJsString(toolCallId));
                        });
                    }

                    // 更新 ChatEntry
                    for (int i = sessionState.chatEntries.size() - 1; i >= 0; i--) {
                        ChatEntry entry = sessionState.chatEntries.get(i);
                        if (entry.type == ChatEntry.Type.TOOL_CALL
                                && toolCallId.equals(entry.toolCallId)
                                && entry.toolResult == null) {
                            entry.toolResult = result;
                            break;
                        }
                    }
                }

                @Override
                public void onUsage(LlmResponse.Usage usage) {
                    sessionState.lastUsage = usage;
                }

                @Override
                public void onComplete(String fullResponse) {
                    sessionState.isProcessing = false;
                    sessionState.chatEntries.removeIf(e -> e.type == ChatEntry.Type.THINKING);

                    boolean hasAssistant = false;
                    for (ChatEntry entry : sessionState.chatEntries) {
                        if (entry.type == ChatEntry.Type.ASSISTANT) {
                            hasAssistant = true;
                        }
                    }
                    if (!hasAssistant && fullResponse != null && !fullResponse.isEmpty()) {
                        sessionState.chatEntries.add(ChatEntry.assistant(fullResponse));
                    }

                    // 清理待审批命令
                    pendingCommands.clear();

                    // 推送 Token 统计和耗时到前端
                    long elapsedMs = System.currentTimeMillis() - sessionState.startTime;
                    if (sessionState.lastUsage != null) {
                        pushUsageToJs(sessionState.lastUsage, elapsedMs);
                    }

                    // 更新会话列表（标题可能已改变）
                    SwingUtilities.invokeLater(() -> pushSessionListToJs());

                    pushComplete();
                }

                @Override
                public void onError(String error) {
                    sessionState.isProcessing = false;
                    sessionState.chatEntries.removeIf(e -> e.type == ChatEntry.Type.THINKING);
                    sessionState.chatEntries.add(ChatEntry.error(error));

                    // 清理待审批命令
                    pendingCommands.clear();

                    pushToJs("clearAllProgress", "");
                    pushToJs("clearAllRunButtons", "");
                    pushError(error);
                }
            });
        });
    }

    // ========== Session Management ==========

    private void createNewSession() {
        String newId = agentService.createSession();
        sessionStates.put(newId, new SessionState());
        pushSessionListToJs();
        pushToJs("clearMessages", "");
    }

    private void switchToSession(String sessionId) {
        agentService.switchSession(sessionId);
        pushHistoryToJs();
        pushSessionListToJs();
    }

    private void deleteSession(String sessionId) {
        sessionStates.remove(sessionId);
        agentService.deleteSession(sessionId);
        pushHistoryToJs();
        pushSessionListToJs();
    }

    private void clearChat() {
        agentService.resetConversation();
        String activeId = agentService.getActiveSessionId();
        SessionState state = sessionStates.get(activeId);
        if (state != null) {
            state.chatEntries.clear();
            state.accumulatedContent = new StringBuilder();
        }
        pushToJs("clearMessages", "");
    }

    private void onSettingsChanged() {
        SwingUtilities.invokeLater(this::pushModelListToJs);
    }

    @Override
    public void dispose() {
        AiAgentSettings.getInstance().removeChangeListener(settingsChangeListener);
        if (jsQuery != null) {
            Disposer.dispose(jsQuery);
        }
        if (browser != null) {
            Disposer.dispose(browser);
        }
    }

    // ========== Inner Classes ==========

    /**
     * 按会话追踪的状态：包含聊天条目、处理状态和累积内容
     */
    private static class SessionState {
        List<ChatEntry> chatEntries = new ArrayList<>();
        volatile boolean isProcessing = false;
        StringBuilder accumulatedContent = new StringBuilder();
        long startTime;
        LlmResponse.Usage lastUsage;
    }

    private static class ChatEntry {
        enum Type { USER, ASSISTANT, TOOL_CALL, THINKING, ERROR }

        final Type type;
        String content;
        String toolCallId;
        String toolName;
        String toolArgs;
        String toolResult;

        private ChatEntry(Type type, String content) {
            this.type = type;
            this.content = content;
        }

        static ChatEntry user(String content) {
            return new ChatEntry(Type.USER, content);
        }

        static ChatEntry assistant(String content) {
            return new ChatEntry(Type.ASSISTANT, content);
        }

        static ChatEntry thinking() {
            return new ChatEntry(Type.THINKING, null);
        }

        static ChatEntry toolCall(String toolCallId, String name, String args) {
            ChatEntry entry = new ChatEntry(Type.TOOL_CALL, null);
            entry.toolCallId = toolCallId;
            entry.toolName = name;
            entry.toolArgs = args;
            return entry;
        }

        static ChatEntry error(String content) {
            return new ChatEntry(Type.ERROR, content);
        }
    }
}

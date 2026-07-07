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
import java.util.List;
import java.util.stream.Collectors;

public class ChatPanel extends JPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(ChatPanel.class);

    private final Project project;
    private final AgentService agentService;

    private JBCefBrowser browser;
    private JBCefJSQuery jsQuery;

    private boolean isProcessing = false;
    private final List<ChatEntry> chatEntries = new ArrayList<>();
    private final Runnable settingsChangeListener = this::onSettingsChanged;
    private volatile int currentGeneration = 0;

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
        pushToJs("showToolCall",
                escapeJsString(name) + "," + escapeJsString(args != null ? args : ""));
    }

    private void pushToolCallEnd(String name, String result) {
        pushToJs("updateToolCall",
                escapeJsString(name) + "," + escapeJsString(result != null ? result : ""));
    }

    private void pushComplete() {
        pushToJs("onComplete", "");
    }

    private void pushError(String error) {
        pushToJs("onError", escapeJsString(error));
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
        json.append("]");
        pushToJs("loadHistory", escapeJsString(json.toString()));
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

    // ========== Send Message ==========

    private void sendMessage(String text) {
        if (text == null || text.trim().isEmpty() || isProcessing) return;

        isProcessing = true;
        chatEntries.add(ChatEntry.user(text));

        String sessionId = agentService.getActiveSessionId();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final int generation = ++currentGeneration;
            agentService.sendMessage(sessionId, text, new AgentService.AgentListener() {
                final StringBuilder accumulatedContent = new StringBuilder();

                @Override
                public void onThinking() {
                }

                @Override
                public void onContent(String content) {
                    if (generation != currentGeneration) return;
                    accumulatedContent.append(content);

                    boolean replaced = false;
                    for (int i = chatEntries.size() - 1; i >= 0; i--) {
                        if (chatEntries.get(i).type == ChatEntry.Type.THINKING) {
                            chatEntries.set(i, ChatEntry.assistant(accumulatedContent.toString()));
                            replaced = true;
                            break;
                        }
                    }
                    if (!replaced) {
                        for (int i = chatEntries.size() - 1; i >= 0; i--) {
                            if (chatEntries.get(i).type == ChatEntry.Type.ASSISTANT) {
                                chatEntries.get(i).content = accumulatedContent.toString();
                                break;
                            }
                        }
                    }

                    pushContent(content);
                }

                @Override
                public void onToolCallStart(String toolName, String arguments) {
                    if (generation != currentGeneration) return;
                    chatEntries.add(ChatEntry.toolCall(toolName, arguments));
                    pushToolCallStart(toolName, arguments);
                }

                @Override
                public void onToolCallEnd(String toolName, String result) {
                    if (generation != currentGeneration) return;
                    for (int i = chatEntries.size() - 1; i >= 0; i--) {
                        ChatEntry entry = chatEntries.get(i);
                        if (entry.type == ChatEntry.Type.TOOL_CALL
                                && toolName.equals(entry.toolName)
                                && entry.toolResult == null) {
                            entry.toolResult = result;
                            break;
                        }
                    }
                    pushToolCallEnd(toolName, result);
                }

                @Override
                public void onComplete(String fullResponse) {
                    if (generation != currentGeneration) return;
                    chatEntries.removeIf(e -> e.type == ChatEntry.Type.THINKING);

                    boolean hasAssistant = false;
                    for (ChatEntry entry : chatEntries) {
                        if (entry.type == ChatEntry.Type.ASSISTANT) {
                            hasAssistant = true;
                        }
                    }
                    if (!hasAssistant && fullResponse != null && !fullResponse.isEmpty()) {
                        chatEntries.add(ChatEntry.assistant(fullResponse));
                    }

                    SwingUtilities.invokeLater(() -> {
                        isProcessing = false;
                        pushSessionListToJs();
                    });

                    pushComplete();
                }

                @Override
                public void onError(String error) {
                    if (generation != currentGeneration) return;
                    chatEntries.removeIf(e -> e.type == ChatEntry.Type.THINKING);
                    chatEntries.add(ChatEntry.error(error));

                    SwingUtilities.invokeLater(() -> {
                        isProcessing = false;
                    });

                    pushError(error);
                }
            });
        });
    }

    // ========== Session Management ==========

    private void createNewSession() {
        currentGeneration++;
        agentService.createSession();
        chatEntries.clear();
        pushSessionListToJs();
        pushToJs("clearMessages", "");
    }

    private void switchToSession(String sessionId) {
        currentGeneration++;
        agentService.switchSession(sessionId);
        chatEntries.clear();
        pushHistoryToJs();
        pushSessionListToJs();
    }

    private void deleteSession(String sessionId) {
        currentGeneration++;
        agentService.deleteSession(sessionId);
        chatEntries.clear();
        pushHistoryToJs();
        pushSessionListToJs();
    }

    private void clearChat() {
        agentService.resetConversation();
        chatEntries.clear();
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

    private static class ChatEntry {
        enum Type { USER, ASSISTANT, TOOL_CALL, THINKING, ERROR }

        final Type type;
        String content;
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

        static ChatEntry toolCall(String name, String args) {
            ChatEntry entry = new ChatEntry(Type.TOOL_CALL, null);
            entry.toolName = name;
            entry.toolArgs = args;
            return entry;
        }

        static ChatEntry error(String content) {
            return new ChatEntry(Type.ERROR, content);
        }
    }
}

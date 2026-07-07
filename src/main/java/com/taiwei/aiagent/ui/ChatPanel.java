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

    private JPanel tabContainer;
    private JComboBox<String> modelSelector;

    private boolean isProcessing = false;
    private final List<ChatEntry> chatEntries = new ArrayList<>();

    private static final JBColor TAB_BAR_BG = new JBColor(new Color(0xFA, 0xFA, 0xFA), new Color(0x30, 0x30, 0x30));
    private static final JBColor TAB_ACTIVE_BG = new JBColor(new Color(0xE3, 0xF2, 0xFD), new Color(0x1E, 0x3A, 0x5F));
    private static final JBColor TAB_INACTIVE_BG = new JBColor(new Color(0xEE, 0xEE, 0xEE), new Color(0x38, 0x38, 0x38));
    private static final JBColor TAB_HOVER_BG = new JBColor(new Color(0xE0, 0xE0, 0xE0), new Color(0x42, 0x42, 0x42));
    private static final JBColor TAB_ACTIVE_TEXT = new JBColor(new Color(0x15, 0x65, 0xC0), new Color(0x90, 0xCA, 0xF9));
    private static final JBColor TAB_INACTIVE_TEXT = new JBColor(new Color(0x75, 0x75, 0x75), new Color(0x99, 0x99, 0x99));
    private static final JBColor TAB_ACTIVE_BORDER = new JBColor(new Color(0x90, 0xCA, 0xF9), new Color(0x3D, 0x7E, 0xBB));
    private static final JBColor DIVIDER_COLOR = new JBColor(new Color(0xE8, 0xE8, 0xE8), new Color(0x44, 0x44, 0x44));
    private static final JBColor ACCENT_COLOR = new JBColor(new Color(0x1E, 0x88, 0xE5), new Color(0x42, 0xA5, 0xF5));
    private static final JBColor ERROR_MSG_BORDER = new JBColor(new Color(0xE5, 0x39, 0x35), new Color(0xEF, 0x53, 0x50));
    private static final JBColor NEW_BTN_HOVER_BG = new JBColor(new Color(0xE3, 0xF2, 0xFD), new Color(0x1A, 0x3A, 0x5C));

    private static final Font TAB_FONT = new Font("SansSerif", Font.PLAIN, 12);

    public ChatPanel(Project project) {
        this.project = project;
        this.agentService = new AgentService(project);
        setLayout(new BorderLayout());
        setBackground(TAB_BAR_BG);
        initUI();
        loadSessions();
    }

    private void initUI() {
        add(createTopBar(), BorderLayout.NORTH);
        initBrowser();
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
                    pushHistoryToJs();
                }
            }
        }, browser.getCefBrowser());

        add(browser.getComponent(), BorderLayout.CENTER);
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
                default:
                    LOG.warn("Unknown JS action: " + action);
            }
        } catch (Exception e) {
            LOG.error("Error handling JS message: " + request, e);
        }
    }

    // ========== Java → JS Push ==========

    private void pushToJs(String func, String data) {
        String js = "if(typeof " + func + "==='function'){" + func + "(" + data + ");}";
        CefBrowser cef = browser.getCefBrowser();
        if (cef != null) {
            cef.executeJavaScript(js, "taiwei-push", 0);
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
        pushToJs("loadHistory", json.toString());
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
            agentService.sendMessage(sessionId, text, new AgentService.AgentListener() {
                final StringBuilder accumulatedContent = new StringBuilder();

                @Override
                public void onThinking() {
                }

                @Override
                public void onContent(String content) {
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
                    chatEntries.add(ChatEntry.toolCall(toolName, arguments));
                    pushToolCallStart(toolName, arguments);
                }

                @Override
                public void onToolCallEnd(String toolName, String result) {
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
                        refreshSessionList();
                    });

                    pushComplete();
                }

                @Override
                public void onError(String error) {
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

    private void loadSessions() {
        rebuildTabs();
        pushHistoryToJs();
    }

    private void refreshSessionList() {
        rebuildTabs();
    }

    private void createNewSession() {
        agentService.createSession();
        chatEntries.clear();
        rebuildTabs();
        pushToJs("clearMessages", "");
    }

    private void switchToSession(String sessionId) {
        agentService.switchSession(sessionId);
        loadChatHistory();
        rebuildTabs();
    }

    private void deleteSession(String sessionId) {
        agentService.deleteSession(sessionId);
        chatEntries.clear();
        loadChatHistory();
        rebuildTabs();
    }

    private void clearChat() {
        agentService.resetConversation();
        chatEntries.clear();
        pushToJs("clearMessages", "");
    }

    private void loadChatHistory() {
        chatEntries.clear();
        pushHistoryToJs();
    }

    // ========== Top Bar: Session Tabs + Actions ==========

    private JPanel createTopBar() {
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(TAB_BAR_BG);
        topBar.setOpaque(true);
        topBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, DIVIDER_COLOR),
                com.intellij.util.ui.JBUI.Borders.empty(5, 8, 5, 8)
        ));

        tabContainer = new JPanel();
        tabContainer.setLayout(new BoxLayout(tabContainer, BoxLayout.X_AXIS));
        tabContainer.setBackground(TAB_BAR_BG);
        tabContainer.setOpaque(true);

        JScrollPane tabScrollPane = new JScrollPane(tabContainer);
        tabScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        tabScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        tabScrollPane.getViewport().setBackground(TAB_BAR_BG);
        tabScrollPane.setBorder(com.intellij.util.ui.JBUI.Borders.empty());
        tabScrollPane.setOpaque(false);

        topBar.add(tabScrollPane, BorderLayout.CENTER);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightPanel.setBackground(TAB_BAR_BG);
        rightPanel.setOpaque(true);

        JButton newSessionBtn = new JButton("+ \u65b0\u5efa");
        styleFlatButton(newSessionBtn);
        newSessionBtn.addActionListener(e -> createNewSession());
        rightPanel.add(newSessionBtn);

        JButton clearBtn = new JButton("\u6e05\u7a7a");
        styleFlatButton(clearBtn);
        clearBtn.addActionListener(e -> clearChat());
        rightPanel.add(clearBtn);

        modelSelector = new JComboBox<>();
        modelSelector.setFont(TAB_FONT);
        loadModelList();
        modelSelector.addActionListener(e -> {
            int idx = modelSelector.getSelectedIndex();
            if (idx >= 0) {
                AiAgentSettings.getInstance().setActiveModelIndex(idx);
            }
        });
        rightPanel.add(modelSelector);

        topBar.add(rightPanel, BorderLayout.EAST);

        return topBar;
    }

    private void rebuildTabs() {
        tabContainer.removeAll();
        List<SessionManager.SessionInfo> sessions = agentService.listSessions();
        String activeId = agentService.getActiveSessionId();

        for (int i = 0; i < sessions.size(); i++) {
            SessionManager.SessionInfo info = sessions.get(i);
            String title = info.getTitle() != null && !info.getTitle().isEmpty()
                    ? info.getTitle() : "\u65b0\u4f1a\u8bdd";
            boolean isActive = info.getId().equals(activeId);
            if (i > 0) {
                tabContainer.add(Box.createHorizontalStrut(4));
            }
            tabContainer.add(createTab(info.getId(), title, isActive));
        }

        tabContainer.add(Box.createHorizontalStrut(8));
        tabContainer.revalidate();
        tabContainer.repaint();
    }

    private JPanel createTab(String sessionId, String title, boolean isActive) {
        JPanel tab = new JPanel(new BorderLayout(4, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
            }
        };
        tab.setOpaque(false);
        tab.setBackground(isActive ? TAB_ACTIVE_BG : TAB_INACTIVE_BG);
        tab.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                        isActive ? TAB_ACTIVE_BORDER : DIVIDER_COLOR, 1, true),
                com.intellij.util.ui.JBUI.Borders.empty(4, 12, 4, 6)
        ));
        tab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        tab.setMaximumSize(new Dimension(200, 30));

        JLabel label = new JLabel(truncateTitle(title, 12));
        label.setFont(TAB_FONT);
        label.setForeground(isActive ? TAB_ACTIVE_TEXT : TAB_INACTIVE_TEXT);
        label.setOpaque(false);
        tab.add(label, BorderLayout.CENTER);

        JLabel closeBtn = new JLabel(" \u00d7");
        closeBtn.setFont(new Font("SansSerif", Font.PLAIN, 14));
        closeBtn.setForeground(isActive ? TAB_ACTIVE_TEXT : TAB_INACTIVE_TEXT);
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.setToolTipText("\u5173\u95ed\u4f1a\u8bdd");
        closeBtn.setOpaque(false);
        Color closeNormal = isActive ? TAB_ACTIVE_TEXT : TAB_INACTIVE_TEXT;
        closeBtn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                e.consume();
                deleteSession(sessionId);
            }

            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                closeBtn.setForeground(ERROR_MSG_BORDER);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                closeBtn.setForeground(closeNormal);
            }
        });
        tab.add(closeBtn, BorderLayout.EAST);

        tab.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                switchToSession(sessionId);
            }

            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                if (!isActive) tab.setBackground(TAB_HOVER_BG);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                tab.setBackground(isActive ? TAB_ACTIVE_BG : TAB_INACTIVE_BG);
            }
        });

        return tab;
    }

    private static String truncateTitle(String title, int maxLen) {
        if (title.length() <= maxLen) return title;
        return title.substring(0, maxLen) + "...";
    }

    private void styleFlatButton(JButton btn) {
        btn.setFont(TAB_FONT);
        btn.setForeground(ACCENT_COLOR);
        btn.setBackground(TAB_BAR_BG);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT_COLOR, 1),
                com.intellij.util.ui.JBUI.Borders.empty(2, 10)
        ));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);

        btn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                btn.setContentAreaFilled(true);
                btn.setBackground(NEW_BTN_HOVER_BG);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                btn.setContentAreaFilled(false);
            }
        });
    }

    // ========== Model Management ==========

    private void loadModelList() {
        modelSelector.removeAllItems();
        List<AiAgentSettings.ModelConfig> configs =
                AiAgentSettings.getInstance().getModelConfigs();
        int activeIndex = AiAgentSettings.getInstance().getActiveModelIndex();
        for (AiAgentSettings.ModelConfig config : configs) {
            String displayName = config.name.isEmpty() ? config.modelName : config.name;
            modelSelector.addItem(displayName);
        }
        if (activeIndex >= 0 && activeIndex < modelSelector.getItemCount()) {
            modelSelector.setSelectedIndex(activeIndex);
        }
    }

    @Override
    public void dispose() {
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

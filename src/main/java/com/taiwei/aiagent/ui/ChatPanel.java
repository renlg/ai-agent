package com.taiwei.aiagent.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.taiwei.aiagent.agent.AgentContext;
import com.taiwei.aiagent.agent.AgentService;
import com.taiwei.aiagent.agent.SessionManager;
import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.settings.AiAgentSettings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

public class ChatPanel extends JPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(ChatPanel.class);

    private final Project project;
    private final AgentService agentService;

    private DefaultListModel<SessionItem> sessionListModel;
    private JList<SessionItem> sessionList;

    private JEditorPane chatDisplay;
    private JScrollPane chatScrollPane;
    private JTextArea inputArea;
    private JButton sendButton;
    private JComboBox<String> modelSelector;

    private boolean isProcessing = false;
    private final List<ChatEntry> chatEntries = new ArrayList<>();

    private static final JBColor USER_BUBBLE_BG = new JBColor(new Color(0xDC, 0xF8, 0xC6), new Color(0x2E, 0x4D, 0x2E));
    private static final JBColor ASSISTANT_BUBBLE_BG = new JBColor(new Color(0xF0, 0xF0, 0xF0), new Color(0x3C, 0x3C, 0x3C));
    private static final JBColor TOOL_BUBBLE_BG = new JBColor(new Color(0xFF, 0xF3, 0xE0), new Color(0x4D, 0x3E, 0x1E));
    private static final JBColor ERROR_BUBBLE_BG = new JBColor(new Color(0xFF, 0xEB, 0xEE), new Color(0x4D, 0x1E, 0x1E));
    private static final JBColor CHAT_BG = new JBColor(Color.WHITE, new Color(0x2B, 0x2B, 0x2B));
    private static final JBColor TEXT_COLOR = new JBColor(new Color(0x33, 0x33, 0x33), new Color(0xCC, 0xCC, 0xCC));
    private static final JBColor BORDER_COLOR = new JBColor(new Color(0xE0, 0xE0, 0xE0), new Color(0x55, 0x55, 0x55));
    private static final JBColor TOOL_TEXT_COLOR = new JBColor(new Color(0x79, 0x55, 0x48), new Color(0xBC, 0xAA, 0xA4));
    private static final JBColor ERROR_TEXT_COLOR = new JBColor(new Color(0xC6, 0x28, 0x28), new Color(0xEF, 0x9A, 0x9A));

    public ChatPanel(Project project) {
        this.project = project;
        this.agentService = new AgentService(project);
        setLayout(new BorderLayout());
        initUI();
        loadSessions();
    }

    private void initUI() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setBorder(JBUI.Borders.empty(2, 4));

        JButton newSessionBtn = new JButton("新建会话");
        newSessionBtn.addActionListener(e -> createNewSession());
        toolBar.add(newSessionBtn);

        toolBar.addSeparator();

        JButton clearBtn = new JButton("清空对话");
        clearBtn.addActionListener(e -> clearChat());
        toolBar.add(clearBtn);

        toolBar.addSeparator();

        modelSelector = new JComboBox<>();
        loadModelList();
        modelSelector.addActionListener(e -> {
            int idx = modelSelector.getSelectedIndex();
            if (idx >= 0) {
                AiAgentSettings.getInstance().setActiveModelIndex(idx);
            }
        });
        toolBar.add(modelSelector);

        add(toolBar, BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(180);
        splitPane.setLeftComponent(createSessionPanel());
        splitPane.setRightComponent(createChatAndInputPanel());

        add(splitPane, BorderLayout.CENTER);
    }

    private JPanel createSessionPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(180, 0));
        panel.setBorder(JBUI.Borders.empty());

        sessionListModel = new DefaultListModel<>();
        sessionList = new JList<>(sessionListModel);
        sessionList.setCellRenderer(new SessionCellRenderer());
        sessionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sessionList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                SessionItem item = sessionList.getSelectedValue();
                if (item != null) {
                    switchToSession(item.id);
                }
            }
        });

        JPopupMenu popup = new JPopupMenu();
        JMenuItem deleteItem = new JMenuItem("删除会话");
        deleteItem.addActionListener(e -> {
            SessionItem item = sessionList.getSelectedValue();
            if (item != null) {
                deleteSession(item.id);
            }
        });
        popup.add(deleteItem);
        sessionList.setComponentPopupMenu(popup);

        JScrollPane scrollPane = new JScrollPane(sessionList);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createChatAndInputPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        chatDisplay = new JEditorPane();
        chatDisplay.setContentType("text/html");
        chatDisplay.setEditable(false);
        chatDisplay.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        chatDisplay.setBackground(CHAT_BG);

        chatScrollPane = new JScrollPane(chatDisplay);
        chatScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        chatScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        chatScrollPane.getViewport().setBackground(CHAT_BG);

        panel.add(chatScrollPane, BorderLayout.CENTER);

        JPanel inputPanel = new JPanel(new BorderLayout(4, 0));
        inputPanel.setBorder(JBUI.Borders.empty(4));

        inputArea = new JTextArea(3, 40);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setFont(new Font("SansSerif", Font.PLAIN, 13));
        inputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume();
                    sendMessage();
                }
            }
        });

        JScrollPane inputScrollPane = new JScrollPane(inputArea);
        inputPanel.add(inputScrollPane, BorderLayout.CENTER);

        sendButton = new JButton("发送");
        sendButton.addActionListener(e -> sendMessage());
        inputPanel.add(sendButton, BorderLayout.EAST);

        panel.add(inputPanel, BorderLayout.SOUTH);

        return panel;
    }

    // ========== Session Management ==========

    private void loadSessions() {
        sessionListModel.clear();
        List<SessionManager.SessionInfo> sessions = agentService.listSessions();
        String activeId = agentService.getActiveSessionId();
        int activeIndex = -1;

        for (int i = 0; i < sessions.size(); i++) {
            SessionManager.SessionInfo info = sessions.get(i);
            String title = info.getTitle() != null && !info.getTitle().isEmpty()
                    ? info.getTitle() : "新会话";
            sessionListModel.addElement(new SessionItem(info.getId(), title));
            if (info.getId().equals(activeId)) {
                activeIndex = i;
            }
        }

        if (activeIndex >= 0) {
            sessionList.setSelectedIndex(activeIndex);
        }

        loadChatHistory();
    }

    private void refreshSessionList() {
        String activeId = agentService.getActiveSessionId();
        List<SessionManager.SessionInfo> sessions = agentService.listSessions();

        sessionListModel.clear();
        int activeIndex = -1;
        for (int i = 0; i < sessions.size(); i++) {
            SessionManager.SessionInfo info = sessions.get(i);
            String title = info.getTitle() != null && !info.getTitle().isEmpty()
                    ? info.getTitle() : "新会话";
            sessionListModel.addElement(new SessionItem(info.getId(), title));
            if (info.getId().equals(activeId)) {
                activeIndex = i;
            }
        }

        if (activeIndex >= 0 && sessionList.getSelectedIndex() != activeIndex) {
            sessionList.setSelectedIndex(activeIndex);
        }
    }

    private void createNewSession() {
        agentService.createSession();
        chatEntries.clear();
        refreshChatDisplay();
        refreshSessionList();
    }

    private void switchToSession(String sessionId) {
        agentService.switchSession(sessionId);
        loadChatHistory();
    }

    private void deleteSession(String sessionId) {
        agentService.deleteSession(sessionId);
        chatEntries.clear();
        loadChatHistory();
        refreshSessionList();
    }

    private void clearChat() {
        agentService.resetConversation();
        chatEntries.clear();
        refreshChatDisplay();
    }

    private void loadChatHistory() {
        chatEntries.clear();
        AgentContext ctx = agentService.getContext();
        if (ctx != null) {
            List<ChatMessage> messages = ctx.getConversation().getMessages();
            for (ChatMessage msg : messages) {
                String role = msg.getRole();
                if ("user".equals(role)) {
                    chatEntries.add(ChatEntry.user(msg.getContent()));
                } else if ("assistant".equals(role) && msg.getContent() != null && !msg.getContent().isEmpty()) {
                    chatEntries.add(ChatEntry.assistant(msg.getContent()));
                }
            }
        }
        refreshChatDisplay();
    }

    // ========== Model Management ==========

    private void loadModelList() {
        modelSelector.removeAllItems();
        List<AiAgentSettings.ModelConfig> configs = AiAgentSettings.getInstance().getModelConfigs();
        int activeIndex = AiAgentSettings.getInstance().getActiveModelIndex();
        for (AiAgentSettings.ModelConfig config : configs) {
            String displayName = config.name.isEmpty() ? config.modelName : config.name;
            modelSelector.addItem(displayName);
        }
        if (activeIndex >= 0 && activeIndex < modelSelector.getItemCount()) {
            modelSelector.setSelectedIndex(activeIndex);
        }
    }

    // ========== Send Message ==========

    private void sendMessage() {
        String text = inputArea.getText().trim();
        if (text.isEmpty() || isProcessing) return;

        inputArea.setText("");
        isProcessing = true;
        sendButton.setEnabled(false);

        chatEntries.add(ChatEntry.user(text));
        chatEntries.add(ChatEntry.thinking());
        refreshChatDisplay();

        String sessionId = agentService.getActiveSessionId();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            agentService.sendMessage(sessionId, text, new AgentService.AgentListener() {
                final StringBuilder accumulatedContent = new StringBuilder();

                @Override
                public void onThinking() {
                }

                @Override
                public void onContent(String content) {
                    SwingUtilities.invokeLater(() -> {
                        accumulatedContent.append(content);

                        for (int i = chatEntries.size() - 1; i >= 0; i--) {
                            if (chatEntries.get(i).type == ChatEntry.Type.THINKING) {
                                chatEntries.set(i, ChatEntry.assistant(accumulatedContent.toString()));
                                refreshChatDisplay();
                                return;
                            }
                        }

                        for (int i = chatEntries.size() - 1; i >= 0; i--) {
                            if (chatEntries.get(i).type == ChatEntry.Type.ASSISTANT) {
                                chatEntries.get(i).content = accumulatedContent.toString();
                                break;
                            }
                        }
                        refreshChatDisplay();
                    });
                }

                @Override
                public void onToolCallStart(String toolName, String arguments) {
                    SwingUtilities.invokeLater(() -> {
                        chatEntries.add(ChatEntry.toolCall(toolName, arguments));
                        refreshChatDisplay();
                    });
                }

                @Override
                public void onToolCallEnd(String toolName, String result) {
                    SwingUtilities.invokeLater(() -> {
                        for (int i = chatEntries.size() - 1; i >= 0; i--) {
                            ChatEntry entry = chatEntries.get(i);
                            if (entry.type == ChatEntry.Type.TOOL_CALL
                                    && toolName.equals(entry.toolName)
                                    && entry.toolResult == null) {
                                entry.toolResult = result;
                                break;
                            }
                        }
                        refreshChatDisplay();
                    });
                }

                @Override
                public void onComplete(String fullResponse) {
                    SwingUtilities.invokeLater(() -> {
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

                        isProcessing = false;
                        sendButton.setEnabled(true);
                        refreshChatDisplay();
                        refreshSessionList();
                    });
                }

                @Override
                public void onError(String error) {
                    SwingUtilities.invokeLater(() -> {
                        chatEntries.removeIf(e -> e.type == ChatEntry.Type.THINKING);
                        chatEntries.add(ChatEntry.error(error));
                        isProcessing = false;
                        sendButton.setEnabled(true);
                        refreshChatDisplay();
                    });
                }
            });
        });
    }

    // ========== Chat Display Rendering ==========

    private void refreshChatDisplay() {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><style>");
        sb.append(buildChatCss());
        sb.append("</style></head><body>");

        for (ChatEntry entry : chatEntries) {
            switch (entry.type) {
                case USER:
                    sb.append("<div class='user-msg'>");
                    sb.append(escapeHtml(entry.content));
                    sb.append("</div>");
                    break;
                case ASSISTANT:
                    sb.append("<div class='assistant-msg'>");
                    sb.append(MarkdownRenderer.render(entry.content));
                    sb.append("</div>");
                    break;
                case TOOL_CALL:
                    sb.append("<div class='tool-call'>");
                    sb.append("<b>").append(escapeHtml(entry.toolName)).append("</b>");
                    if (entry.toolArgs != null && !entry.toolArgs.isEmpty()) {
                        String args = entry.toolArgs.length() > 100
                                ? entry.toolArgs.substring(0, 100) + "..."
                                : entry.toolArgs;
                        sb.append("<br><code>").append(escapeHtml(args)).append("</code>");
                    }
                    if (entry.toolResult != null) {
                        String result = entry.toolResult.length() > 300
                                ? entry.toolResult.substring(0, 300) + "..."
                                : entry.toolResult;
                        sb.append("<br>").append(escapeHtml(result));
                    } else {
                        sb.append("<br><i>executing...</i>");
                    }
                    sb.append("</div>");
                    break;
                case THINKING:
                    sb.append("<div class='thinking'><i>thinking...</i></div>");
                    break;
                case ERROR:
                    sb.append("<div class='error-msg'>").append(escapeHtml(entry.content)).append("</div>");
                    break;
            }
        }

        sb.append("</body></html>");
        chatDisplay.setText(sb.toString());

        SwingUtilities.invokeLater(() -> {
            if (chatDisplay.getDocument() != null) {
                chatDisplay.setCaretPosition(chatDisplay.getDocument().getLength());
            }
        });
    }

    private String buildChatCss() {
        String userBg = colorToHex(USER_BUBBLE_BG);
        String assistantBg = colorToHex(ASSISTANT_BUBBLE_BG);
        String toolBg = colorToHex(TOOL_BUBBLE_BG);
        String errorBg = colorToHex(ERROR_BUBBLE_BG);
        String chatBg = colorToHex(CHAT_BG);
        String textCol = colorToHex(TEXT_COLOR);
        String borderCol = colorToHex(BORDER_COLOR);
        String toolTextCol = colorToHex(TOOL_TEXT_COLOR);
        String errorTextCol = colorToHex(ERROR_TEXT_COLOR);

        return "body { font-family: SansSerif; font-size: 13px; margin: 8px; "
                + "background-color: " + chatBg + "; color: " + textCol + "; }"
                + ".user-msg { margin: 6px 0 6px 80px; padding: 8px 12px; "
                + "background-color: " + userBg + "; "
                + "border: 1px solid " + borderCol + "; }"
                + ".assistant-msg { margin: 6px 80px 6px 0; padding: 8px 12px; "
                + "background-color: " + assistantBg + "; "
                + "border: 1px solid " + borderCol + "; }"
                + ".tool-call { margin: 2px 80px 2px 20px; padding: 4px 8px; "
                + "background-color: " + toolBg + "; "
                + "border: 1px solid " + borderCol + "; "
                + "font-size: 12px; color: " + toolTextCol + "; }"
                + ".thinking { margin: 6px 80px 6px 0; padding: 8px 12px; "
                + "color: " + textCol + "; }"
                + ".error-msg { margin: 6px 0; padding: 8px 12px; "
                + "background-color: " + errorBg + "; "
                + "border: 1px solid " + borderCol + "; "
                + "color: " + errorTextCol + "; }"
                + "pre { background-color: " + assistantBg + "; padding: 8px; "
                + "border: 1px solid " + borderCol + "; "
                + "font-family: Monospaced; font-size: 12px; }"
                + "code { font-family: Monospaced; font-size: 12px; }"
                + "pre code { padding: 0; }"
                + "a { color: #2196F3; }"
                + "table { border-collapse: collapse; margin: 4px 0; }"
                + "th, td { border: 1px solid " + borderCol + "; padding: 4px 8px; }"
                + "th { background-color: " + assistantBg + "; }";
    }

    private static String colorToHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    @Override
    public void dispose() {
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

    private static class SessionItem {
        final String id;
        final String title;

        SessionItem(String id, String title) {
            this.id = id;
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private static class SessionCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof SessionItem) {
                setText("  " + ((SessionItem) value).title);
                setBorder(new EmptyBorder(4, 4, 4, 4));
            }
            return this;
        }
    }
}

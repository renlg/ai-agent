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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

public class ChatPanel extends JPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(ChatPanel.class);

    private final Project project;
    private final AgentService agentService;

    private JPanel tabContainer;

    private JEditorPane chatDisplay;
    private JScrollPane chatScrollPane;
    private JTextArea inputArea;
    private JButton sendButton;
    private JComboBox<String> modelSelector;

    private boolean isProcessing = false;
    private final List<ChatEntry> chatEntries = new ArrayList<>();

    // Message bubble colors
    private static final JBColor USER_BUBBLE_BG = new JBColor(new Color(0xE3, 0xF2, 0xFD), new Color(0x1A, 0x3A, 0x5C));
    private static final JBColor ASSISTANT_BUBBLE_BG = new JBColor(new Color(0xF5, 0xF5, 0xF5), new Color(0x3C, 0x3C, 0x3C));
    private static final JBColor TOOL_BUBBLE_BG = new JBColor(new Color(0xFF, 0xF3, 0xE0), new Color(0x4D, 0x3E, 0x1E));
    private static final JBColor ERROR_BUBBLE_BG = new JBColor(new Color(0xFF, 0xEB, 0xEE), new Color(0x4D, 0x1E, 0x1E));
    private static final JBColor CHAT_BG = new JBColor(Color.WHITE, new Color(0x2B, 0x2B, 0x2B));
    private static final JBColor TEXT_COLOR = new JBColor(new Color(0x33, 0x33, 0x33), new Color(0xCC, 0xCC, 0xCC));
    private static final JBColor BORDER_COLOR = new JBColor(new Color(0xE0, 0xE0, 0xE0), new Color(0x55, 0x55, 0x55));
    private static final JBColor TOOL_TEXT_COLOR = new JBColor(new Color(0x79, 0x55, 0x48), new Color(0xBC, 0xAA, 0xA4));
    private static final JBColor ERROR_TEXT_COLOR = new JBColor(new Color(0xC6, 0x28, 0x28), new Color(0xEF, 0x9A, 0x9A));
    private static final JBColor USER_TEXT_COLOR = new JBColor(new Color(0x0D, 0x47, 0xA1), new Color(0x90, 0xCA, 0xF9));

    // Bubble border colors (slightly darker than bg for subtle shadow effect)
    private static final JBColor USER_BUBBLE_BORDER = new JBColor(new Color(0xBB, 0xDE, 0xFB), new Color(0x2A, 0x5A, 0x8C));
    private static final JBColor ASSISTANT_BUBBLE_BORDER = new JBColor(new Color(0xE0, 0xE0, 0xE0), new Color(0x4A, 0x4A, 0x4A));
    private static final JBColor TOOL_BUBBLE_BORDER = new JBColor(new Color(0xFF, 0xCC, 0x80), new Color(0x6D, 0x5E, 0x3E));
    private static final JBColor ERROR_BUBBLE_BORDER = new JBColor(new Color(0xEF, 0x9A, 0x9A), new Color(0x6D, 0x3E, 0x3E));

    // Tab bar colors
    private static final JBColor TAB_ACTIVE_BG = new JBColor(new Color(0xE3, 0xF2, 0xFD), new Color(0x1E, 0x3A, 0x5F));
    private static final JBColor TAB_INACTIVE_BG = new JBColor(new Color(0xEE, 0xEE, 0xEE), new Color(0x38, 0x38, 0x38));
    private static final JBColor TAB_HOVER_BG = new JBColor(new Color(0xE0, 0xE0, 0xE0), new Color(0x42, 0x42, 0x42));
    private static final JBColor TAB_ACTIVE_TEXT = new JBColor(new Color(0x15, 0x65, 0xC0), new Color(0x90, 0xCA, 0xF9));
    private static final JBColor TAB_INACTIVE_TEXT = new JBColor(new Color(0x75, 0x75, 0x75), new Color(0x99, 0x99, 0x99));
    private static final JBColor TAB_BAR_BG = new JBColor(new Color(0xFA, 0xFA, 0xFA), new Color(0x30, 0x30, 0x30));
    private static final JBColor TAB_ACTIVE_BORDER = new JBColor(new Color(0x90, 0xCA, 0xF9), new Color(0x3D, 0x7E, 0xBB));

    // Input area colors
    private static final JBColor INPUT_BG = new JBColor(Color.WHITE, new Color(0x36, 0x36, 0x36));
    private static final JBColor INPUT_BORDER = new JBColor(new Color(0xBB, 0xDE, 0xFB), new Color(0x4A, 0x6A, 0x8A));

    // Accent / button colors
    private static final JBColor ACCENT_COLOR = new JBColor(new Color(0x1E, 0x88, 0xE5), new Color(0x42, 0xA5, 0xF5));
    private static final JBColor SEND_BTN_BG = new JBColor(new Color(0x1E, 0x88, 0xE5), new Color(0x3D, 0x8C, 0xD4));
    private static final JBColor SEND_BTN_HOVER = new JBColor(new Color(0x19, 0x76, 0xD2), new Color(0x33, 0x7A, 0xB7));
    private static final JBColor NEW_BTN_HOVER_BG = new JBColor(new Color(0xE3, 0xF2, 0xFD), new Color(0x1A, 0x3A, 0x5C));

    // Fonts
    private static final Font CHAT_FONT = new Font("SansSerif", Font.PLAIN, 13);
    private static final Font TAB_FONT = new Font("SansSerif", Font.PLAIN, 12);
    private static final Font INPUT_FONT = new Font("SansSerif", Font.PLAIN, 13);
    private static final Font SEND_FONT = new Font("SansSerif", Font.BOLD, 18);

    public ChatPanel(Project project) {
        this.project = project;
        this.agentService = new AgentService(project);
        setLayout(new BorderLayout());
        setBackground(CHAT_BG);
        initUI();
        loadSessions();
    }

    private void initUI() {
        add(createTopBar(), BorderLayout.NORTH);

        chatDisplay = new JEditorPane();
        chatDisplay.setContentType("text/html");
        chatDisplay.setEditable(false);
        chatDisplay.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        chatDisplay.setBackground(CHAT_BG);
        chatDisplay.setFont(CHAT_FONT);

        chatScrollPane = new JScrollPane(chatDisplay);
        chatScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        chatScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        chatScrollPane.getViewport().setBackground(CHAT_BG);
        chatScrollPane.setBorder(JBUI.Borders.empty());

        add(chatScrollPane, BorderLayout.CENTER);
        add(createInputPanel(), BorderLayout.SOUTH);
    }

    // ========== Top Bar: Session Tabs + Actions ==========

    private JPanel createTopBar() {
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(TAB_BAR_BG);
        topBar.setOpaque(true);
        topBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR),
                JBUI.Borders.empty(5, 8, 5, 8)
        ));

        tabContainer = new JPanel();
        tabContainer.setLayout(new BoxLayout(tabContainer, BoxLayout.X_AXIS));
        tabContainer.setBackground(TAB_BAR_BG);
        tabContainer.setOpaque(true);

        JScrollPane tabScrollPane = new JScrollPane(tabContainer);
        tabScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        tabScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        tabScrollPane.getViewport().setBackground(TAB_BAR_BG);
        tabScrollPane.setBorder(JBUI.Borders.empty());
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

    private void styleFlatButton(JButton btn) {
        btn.setFont(TAB_FONT);
        btn.setForeground(ACCENT_COLOR);
        btn.setBackground(TAB_BAR_BG);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT_COLOR, 1),
                JBUI.Borders.empty(2, 10)
        ));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);

        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                btn.setContentAreaFilled(true);
                btn.setBackground(NEW_BTN_HOVER_BG);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                btn.setContentAreaFilled(false);
            }
        });
    }

    // ========== Tab Rendering ==========

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
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
            }
        };
        tab.setOpaque(false);
        tab.setBackground(isActive ? TAB_ACTIVE_BG : TAB_INACTIVE_BG);
        tab.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                        isActive ? TAB_ACTIVE_BORDER : BORDER_COLOR, 1, true
                ),
                JBUI.Borders.empty(4, 12, 4, 6)
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
        closeBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                e.consume();
                deleteSession(sessionId);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                closeBtn.setForeground(ERROR_TEXT_COLOR);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                closeBtn.setForeground(closeNormal);
            }
        });
        tab.add(closeBtn, BorderLayout.EAST);

        tab.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                switchToSession(sessionId);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                if (!isActive) {
                    tab.setBackground(TAB_HOVER_BG);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                tab.setBackground(isActive ? TAB_ACTIVE_BG : TAB_INACTIVE_BG);
            }
        });

        return tab;
    }

    private static String truncateTitle(String title, int maxLen) {
        if (title.length() <= maxLen) return title;
        return title.substring(0, maxLen) + "...";
    }

    // ========== Bottom: Input Area ==========

    private JPanel createInputPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBackground(CHAT_BG);
        panel.setOpaque(true);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COLOR),
                JBUI.Borders.empty(8, 12)
        ));

        inputArea = new JTextArea(3, 40);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setFont(INPUT_FONT);
        inputArea.setBackground(INPUT_BG);
        inputArea.setForeground(TEXT_COLOR);
        inputArea.setCaretColor(TEXT_COLOR);
        inputArea.setBorder(new EmptyBorder(8, 10, 8, 10));
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
        inputScrollPane.setBorder(BorderFactory.createLineBorder(INPUT_BORDER, 1, true));
        inputScrollPane.getViewport().setBackground(INPUT_BG);

        panel.add(inputScrollPane, BorderLayout.CENTER);

        sendButton = new JButton("\u27a4");
        sendButton.setFont(SEND_FONT);
        sendButton.setForeground(Color.WHITE);
        sendButton.setBackground(SEND_BTN_BG);
        sendButton.setPreferredSize(new Dimension(48, 48));
        sendButton.setBorder(new EmptyBorder(4, 4, 4, 4));
        sendButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sendButton.setFocusPainted(false);
        sendButton.addActionListener(e -> sendMessage());

        sendButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (sendButton.isEnabled()) {
                    sendButton.setBackground(SEND_BTN_HOVER);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                sendButton.setBackground(SEND_BTN_BG);
            }
        });

        panel.add(sendButton, BorderLayout.EAST);

        return panel;
    }

    // ========== Session Management ==========

    private void loadSessions() {
        rebuildTabs();
        loadChatHistory();
    }

    private void refreshSessionList() {
        rebuildTabs();
    }

    private void createNewSession() {
        agentService.createSession();
        chatEntries.clear();
        refreshChatDisplay();
        rebuildTabs();
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
        sb.append("<html><head><style type=\"text/css\">");
        sb.append(buildChatCss());
        sb.append("</style></head><body>");

        for (ChatEntry entry : chatEntries) {
            switch (entry.type) {
                case USER:
                    sb.append("<div class=\"user-msg\">");
                    sb.append(escapeHtml(entry.content));
                    sb.append("</div>");
                    break;
                case ASSISTANT:
                    sb.append("<div class=\"assistant-msg\">");
                    sb.append(MarkdownRenderer.render(entry.content));
                    sb.append("</div>");
                    break;
                case TOOL_CALL:
                    sb.append("<div class=\"tool-call\">");
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
                    sb.append("<div class=\"thinking\"><i>thinking...</i></div>");
                    break;
                case ERROR:
                    sb.append("<div class=\"error-msg\">").append(escapeHtml(entry.content)).append("</div>");
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
        String userTextCol = colorToHex(USER_TEXT_COLOR);
        String userBorderCol = colorToHex(USER_BUBBLE_BORDER);
        String assistantBorderCol = colorToHex(ASSISTANT_BUBBLE_BORDER);
        String toolBorderCol = colorToHex(TOOL_BUBBLE_BORDER);
        String errorBorderCol = colorToHex(ERROR_BUBBLE_BORDER);

        String bdr = "1px solid ";

        return "body { font-family: SansSerif; font-size: 13px; "
                + "margin-top: 12px; margin-right: 12px; margin-bottom: 12px; margin-left: 12px; "
                + "background-color: " + chatBg + "; color: " + textCol + "; }"

                + ".user-msg { "
                + "margin-top: 10px; margin-bottom: 10px; margin-left: 60px; "
                + "padding-top: 10px; padding-right: 14px; padding-bottom: 10px; padding-left: 14px; "
                + "background-color: " + userBg + "; "
                + "border: " + bdr + userBorderCol + "; "
                + "color: " + userTextCol + "; }"

                + ".assistant-msg { "
                + "margin-top: 10px; margin-right: 60px; margin-bottom: 10px; "
                + "padding-top: 10px; padding-right: 14px; padding-bottom: 10px; padding-left: 14px; "
                + "background-color: " + assistantBg + "; "
                + "border: " + bdr + assistantBorderCol + "; "
                + "color: " + textCol + "; }"

                + ".tool-call { "
                + "margin-top: 4px; margin-right: 60px; margin-bottom: 4px; margin-left: 20px; "
                + "padding-top: 6px; padding-right: 10px; padding-bottom: 6px; padding-left: 10px; "
                + "background-color: " + toolBg + "; "
                + "border: " + bdr + toolBorderCol + "; "
                + "font-size: 12px; color: " + toolTextCol + "; }"

                + ".thinking { "
                + "margin-top: 10px; margin-right: 60px; margin-bottom: 10px; "
                + "padding-top: 10px; padding-right: 14px; padding-bottom: 10px; padding-left: 14px; "
                + "color: " + textCol + "; }"

                + ".error-msg { "
                + "margin-top: 10px; margin-bottom: 10px; "
                + "padding-top: 10px; padding-right: 14px; padding-bottom: 10px; padding-left: 14px; "
                + "background-color: " + errorBg + "; "
                + "border: " + bdr + errorBorderCol + "; "
                + "color: " + errorTextCol + "; }"

                + "pre { "
                + "background-color: " + assistantBg + "; "
                + "padding-top: 10px; padding-right: 10px; padding-bottom: 10px; padding-left: 10px; "
                + "border: " + bdr + assistantBorderCol + "; "
                + "font-family: Monospaced; font-size: 12px; }"

                + "code { font-family: Monospaced; font-size: 12px; }"
                + "pre code { padding-top: 0px; padding-right: 0px; padding-bottom: 0px; padding-left: 0px; }"

                + "a { color: #1E88E5; }"

                + "table { margin-top: 6px; margin-bottom: 6px; }"
                + "th { border: " + bdr + borderCol + "; "
                + "padding-top: 6px; padding-right: 10px; padding-bottom: 6px; padding-left: 10px; "
                + "background-color: " + assistantBg + "; }"
                + "td { border: " + bdr + borderCol + "; "
                + "padding-top: 6px; padding-right: 10px; padding-bottom: 6px; padding-left: 10px; }"

                + "p { margin-top: 4px; margin-bottom: 4px; }"
                + "ul { margin-top: 4px; margin-bottom: 4px; padding-left: 20px; }"
                + "ol { margin-top: 4px; margin-bottom: 4px; padding-left: 20px; }";
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
}

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

    private static final JBColor CHAT_BG = new JBColor(Color.WHITE, new Color(0x2B, 0x2B, 0x2B));
    private static final JBColor TEXT_COLOR = new JBColor(new Color(0x33, 0x33, 0x33), new Color(0xCC, 0xCC, 0xCC));
    private static final JBColor SECONDARY_TEXT = new JBColor(new Color(0x88, 0x88, 0x88), new Color(0x88, 0x88, 0x88));
    private static final JBColor DIVIDER_COLOR = new JBColor(new Color(0xE8, 0xE8, 0xE8), new Color(0x44, 0x44, 0x44));

    private static final JBColor USER_MSG_BORDER = new JBColor(new Color(0x1E, 0x88, 0xE5), new Color(0x42, 0xA5, 0xF5));
    private static final JBColor ASSISTANT_MSG_BORDER = new JBColor(new Color(0x43, 0xA0, 0x47), new Color(0x66, 0xBB, 0x6A));
    private static final JBColor TOOL_MSG_BORDER = new JBColor(new Color(0xF5, 0x7C, 0x00), new Color(0xFF, 0xB7, 0x4D));
    private static final JBColor ERROR_MSG_BORDER = new JBColor(new Color(0xE5, 0x39, 0x35), new Color(0xEF, 0x53, 0x50));

    private static final JBColor TAB_ACTIVE_BG = new JBColor(new Color(0xE3, 0xF2, 0xFD), new Color(0x1E, 0x3A, 0x5F));
    private static final JBColor TAB_INACTIVE_BG = new JBColor(new Color(0xEE, 0xEE, 0xEE), new Color(0x38, 0x38, 0x38));
    private static final JBColor TAB_HOVER_BG = new JBColor(new Color(0xE0, 0xE0, 0xE0), new Color(0x42, 0x42, 0x42));
    private static final JBColor TAB_ACTIVE_TEXT = new JBColor(new Color(0x15, 0x65, 0xC0), new Color(0x90, 0xCA, 0xF9));
    private static final JBColor TAB_INACTIVE_TEXT = new JBColor(new Color(0x75, 0x75, 0x75), new Color(0x99, 0x99, 0x99));
    private static final JBColor TAB_BAR_BG = new JBColor(new Color(0xFA, 0xFA, 0xFA), new Color(0x30, 0x30, 0x30));
    private static final JBColor TAB_ACTIVE_BORDER = new JBColor(new Color(0x90, 0xCA, 0xF9), new Color(0x3D, 0x7E, 0xBB));

    private static final JBColor INPUT_BG = new JBColor(Color.WHITE, new Color(0x36, 0x36, 0x36));
    private static final JBColor INPUT_BORDER = new JBColor(new Color(0xBB, 0xDE, 0xFB), new Color(0x4A, 0x6A, 0x8A));

    private static final JBColor ACCENT_COLOR = new JBColor(new Color(0x1E, 0x88, 0xE5), new Color(0x42, 0xA5, 0xF5));
    private static final JBColor SEND_BTN_BG = new JBColor(new Color(0x1E, 0x88, 0xE5), new Color(0x3D, 0x8C, 0xD4));
    private static final JBColor SEND_BTN_HOVER = new JBColor(new Color(0x19, 0x76, 0xD2), new Color(0x33, 0x7A, 0xB7));
    private static final JBColor NEW_BTN_HOVER_BG = new JBColor(new Color(0xE3, 0xF2, 0xFD), new Color(0x1A, 0x3A, 0x5C));

    private static final Font CHAT_FONT = new Font("SansSerif", Font.PLAIN, 14);
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
                BorderFactory.createMatteBorder(0, 0, 1, 0, DIVIDER_COLOR),
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
                        isActive ? TAB_ACTIVE_BORDER : DIVIDER_COLOR, 1, true
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
                closeBtn.setForeground(ERROR_MSG_BORDER);
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
                BorderFactory.createMatteBorder(1, 0, 0, 0, DIVIDER_COLOR),
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
        String chatBg = colorToHex(CHAT_BG);
        String textCol = colorToHex(TEXT_COLOR);
        String secondaryCol = colorToHex(SECONDARY_TEXT);
        String dividerCol = colorToHex(DIVIDER_COLOR);
        String userCol = colorToHex(USER_MSG_BORDER);
        String assistantCol = colorToHex(ASSISTANT_MSG_BORDER);
        String toolCol = colorToHex(TOOL_MSG_BORDER);
        String errorCol = colorToHex(ERROR_MSG_BORDER);
        String codeBg = colorToHex(new JBColor(new Color(0xF5, 0xF5, 0xF5), new Color(0x3C, 0x3C, 0x3C)));
        String codeBorder = colorToHex(new JBColor(new Color(0xE0, 0xE0, 0xE0), new Color(0x4A, 0x4A, 0x4A)));

        StringBuilder sb = new StringBuilder();
        sb.append("<html><body")
                .append(" style=\"font-family: SansSerif; font-size: 14px;")
                .append(" margin-top: 8px; margin-right: 14px; margin-bottom: 8px; margin-left: 14px;")
                .append(" background-color: ").append(chatBg).append(";")
                .append(" color: ").append(textCol).append(";")
                .append(" line-height: 1.6;\">");

        for (int i = 0; i < chatEntries.size(); i++) {
            ChatEntry entry = chatEntries.get(i);
            if (i > 0) {
                sb.append("<hr color=\"").append(dividerCol).append("\" size=\"1\" noshade>");
            }
            switch (entry.type) {
                case USER:
                    sb.append("<div style=\"margin: 10px 0; padding: 6px 8px 6px 12px; border-left: 4px solid ").append(userCol).append(";\">");
                    sb.append("<div style=\"font-size: 12px; font-weight: bold; color: ").append(userCol).append("; margin-bottom: 4px;\">\u4f60</div>");
                    sb.append("<div style=\"font-size: 14px; color: ").append(textCol).append("; line-height: 1.6;\">");
                    sb.append(escapeHtml(entry.content));
                    sb.append("</div></div>");
                    break;
                case ASSISTANT:
                    sb.append("<div style=\"margin: 10px 0; padding: 6px 8px 6px 12px; border-left: 4px solid ").append(assistantCol).append(";\">");
                    sb.append("<div style=\"font-size: 12px; font-weight: bold; color: ").append(assistantCol).append("; margin-bottom: 4px;\">AI</div>");
                    sb.append("<div style=\"font-size: 14px; color: ").append(textCol).append("; line-height: 1.6;\">");
                    sb.append(inlineMarkdownStyles(MarkdownRenderer.render(entry.content), codeBg, codeBorder, userCol));
                    sb.append("</div></div>");
                    break;
                case TOOL_CALL:
                    sb.append("<div style=\"margin: 10px 0; padding: 6px 8px 6px 12px; border-left: 4px solid ").append(toolCol).append(";\">");
                    sb.append("<div style=\"font-size: 12px; font-weight: bold; color: ").append(toolCol).append("; margin-bottom: 4px;\">\u5de5\u5177 \u00b7 ");
                    sb.append(escapeHtml(entry.toolName)).append("</div>");
                    sb.append("<div style=\"font-size: 14px; color: ").append(textCol).append("; line-height: 1.6;\">");
                    if (entry.toolArgs != null && !entry.toolArgs.isEmpty()) {
                        String args = entry.toolArgs.length() > 100
                                ? entry.toolArgs.substring(0, 100) + "..."
                                : entry.toolArgs;
                        sb.append("<code style=\"font-family: Monospaced; font-size: 12px; background-color: ").append(codeBg).append("; padding: 2px 4px;\">").append(escapeHtml(args)).append("</code>");
                        sb.append("<br>");
                    }
                    if (entry.toolResult != null) {
                        String result = entry.toolResult.length() > 300
                                ? entry.toolResult.substring(0, 300) + "..."
                                : entry.toolResult;
                        sb.append(escapeHtml(result));
                    } else {
                        sb.append("<i>\u6267\u884c\u4e2d...</i>");
                    }
                    sb.append("</div></div>");
                    break;
                case THINKING:
                    sb.append("<div style=\"margin: 10px 0; padding-left: 12px; color: ").append(secondaryCol).append("; font-size: 13px;\"><i>\u601d\u8003\u4e2d...</i></div>");
                    break;
                case ERROR:
                    sb.append("<div style=\"margin: 10px 0; padding: 6px 8px 6px 12px; border-left: 4px solid ").append(errorCol).append(";\">");
                    sb.append("<div style=\"font-size: 12px; font-weight: bold; color: ").append(errorCol).append("; margin-bottom: 4px;\">\u9519\u8bef</div>");
                    sb.append("<div style=\"font-size: 14px; color: ").append(textCol).append("; line-height: 1.6;\">");
                    sb.append(escapeHtml(entry.content));
                    sb.append("</div></div>");
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

    private String inlineMarkdownStyles(String html, String codeBg, String codeBorder, String linkCol) {
        if (html == null || html.isEmpty()) return html;

        html = html.replace("<pre>",
                "<pre style=\"background-color: " + codeBg + "; padding: 10px; border: 1px solid " + codeBorder + "; font-family: Monospaced; font-size: 12px;\">");

        html = html.replaceAll("<pre([^>]*)><code[^>]*>", "<pre$1>");
        html = html.replace("</code></pre>", "</pre>");

        html = html.replace("<code>",
                "<code style=\"font-family: Monospaced; font-size: 12px; background-color: " + codeBg + "; padding: 2px 4px;\">");

        html = html.replace("<a ", "<a style=\"color: " + linkCol + ";\" ");

        html = html.replace("<table>", "<table style=\"margin: 6px 0;\">");

        html = html.replace("<th>",
                "<th style=\"border: 1px solid " + codeBorder + "; padding: 6px 10px; background-color: " + codeBg + ";\">");

        html = html.replace("<td>",
                "<td style=\"border: 1px solid " + codeBorder + "; padding: 6px 10px;\">");

        html = html.replace("<p>", "<p style=\"margin: 4px 0;\">");

        html = html.replace("<ul>", "<ul style=\"margin: 4px 0; padding-left: 20px;\">");
        html = html.replace("<ol>", "<ol style=\"margin: 4px 0; padding-left: 20px;\">");

        return html;
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

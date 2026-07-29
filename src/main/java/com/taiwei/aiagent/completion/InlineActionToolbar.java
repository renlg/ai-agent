package com.taiwei.aiagent.completion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.taiwei.aiagent.settings.AiAgentSettings;
import com.taiwei.aiagent.ui.EditorChatBridge;
import okhttp3.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class InlineActionToolbar {

    private static final String TOOL_WINDOW_ID = "\u592a\u5fae";
    private static final int MAX_CODE_CHARS = 15000;

    private static final String[] ACTION_LABELS = {"\u89e3\u91ca", "\u4fee\u590d", "\u4f18\u5316", "\u6d4b\u8bd5", "\u6ce8\u91ca"};

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private static final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "taiwei-inline-action");
        t.setDaemon(true);
        return t;
    });

    private final Editor editor;
    private final Project project;
    private volatile String selectedText;
    private final String filePath;
    private final String language;

    private JBPopup toolbarPopup;
    private Balloon resultBalloon;
    private volatile boolean processing = false;
    private final AtomicReference<Call> currentCall = new AtomicReference<>();

    public InlineActionToolbar(Editor editor, Project project, String selectedText,
                               String filePath, String language) {
        this.editor = editor;
        this.project = project;
        this.selectedText = selectedText;
        this.filePath = filePath;
        this.language = language;
    }

    public void show() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (editor.isDisposed()) return;
            createAndShowToolbar();
        });
    }

    public void hide() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (toolbarPopup != null) {
                if (!toolbarPopup.isDisposed()) {
                    toolbarPopup.cancel();
                }
                toolbarPopup = null;
            }
            if (resultBalloon != null && !resultBalloon.isDisposed()) {
                resultBalloon.hide();
                resultBalloon = null;
            }
        });
    }

    public void updateSelection(String newText) {
        this.selectedText = newText;
    }

    private void createAndShowToolbar() {
        if (editor.isDisposed()) return;

        JPanel toolbarPanel = createToolbarPanel();

        // A lightweight JBPopup automatically gets native rounded corners and a drop
        // shadow (see AbstractPopup/WindowRoundedCornersManager), matching the same
        // modern look used by quick-doc and intention popups across the IDE.
        toolbarPopup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(toolbarPanel, null)
                .setRequestFocus(false)
                .setFocusable(false)
                .setResizable(false)
                .setMovable(false)
                .setCancelOnClickOutside(false)
                .setCancelOnOtherWindowOpen(false)
                .setCancelOnWindowDeactivation(false)
                .setCancelKeyEnabled(false)
                .setShowShadow(true)
                .setShowBorder(true)
                .createPopup();

        Point location = calculateScreenPosition(toolbarPanel.getPreferredSize());
        if (location != null) {
            toolbarPopup.showInScreenCoordinates(editor.getContentComponent(), location);
        } else {
            toolbarPopup.cancel();
            toolbarPopup = null;
        }
    }

    private JPanel createToolbarPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0));
        panel.setOpaque(true);
        panel.setBackground(JBUI.CurrentTheme.Popup.BACKGROUND);
        panel.setBorder(JBUI.Borders.empty(4, 6));

        for (int i = 0; i < ACTION_LABELS.length; i++) {
            JButton btn = createActionButton(ACTION_LABELS[i], i);
            panel.add(btn);
            if (i < ACTION_LABELS.length - 1) {
                JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
                sep.setForeground(JBUI.CurrentTheme.Popup.borderColor(false));
                sep.setPreferredSize(new Dimension(1, JBUI.scale(16)));
                panel.add(sep);
            }
        }

        return panel;
    }

    private JButton createActionButton(String text, int actionIndex) {
        JButton button = new RoundedHoverButton(text);
        button.setFont(JBUI.Fonts.label(12f));
        button.setForeground(UIUtil.getLabelForeground());
        button.setMargin(JBUI.insets(4, 10));
        button.setFocusable(false);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        button.addActionListener(e -> {
            if (processing) return;
            executeAction(ACTION_LABELS[actionIndex]);
        });

        return button;
    }

    /** Flat text button that paints a rounded, IDE-themed highlight on hover instead of a square background. */
    private static class RoundedHoverButton extends JButton {
        private boolean hovered = false;

        RoundedHoverButton(String text) {
            super(text);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (hovered) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(JBUI.CurrentTheme.ActionButton.hoverBackground());
                int arc = JBUI.scale(6);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    private Point calculateScreenPosition(Dimension popupSize) {
        int selectionEnd = editor.getCaretModel().getOffset();
        if (selectionEnd <= 0) return null;

        com.intellij.openapi.editor.LogicalPosition logPos =
                editor.offsetToLogicalPosition(selectionEnd - 1);
        Point point = editor.visualPositionToXY(
                new com.intellij.openapi.editor.VisualPosition(logPos.line, logPos.column + 1));

        JComponent contentComponent = editor.getContentComponent();
        Point editorScreenLoc = contentComponent.getLocationOnScreen();

        int x = editorScreenLoc.x + point.x + 20;
        int y = editorScreenLoc.y + point.y + editor.getLineHeight() + 4;

        Dimension windowSize = popupSize != null ? popupSize : new Dimension(300, 32);
        Rectangle visibleArea = editor.getScrollingModel().getVisibleArea();
        int visibleRight = editorScreenLoc.x + visibleArea.x + visibleArea.width;
        int visibleBottom = editorScreenLoc.y + visibleArea.y + visibleArea.height;

        if (x + windowSize.width > visibleRight) {
            x = visibleRight - windowSize.width - 10;
        }
        if (y + windowSize.height > visibleBottom) {
            y = editorScreenLoc.y + point.y - windowSize.height - 4;
        }

        return new Point(Math.max(editorScreenLoc.x + 4, x), Math.max(editorScreenLoc.y + 4, y));
    }

    private void executeAction(String action) {
        Call previous = currentCall.getAndSet(null);
        if (previous != null) {
            previous.cancel();
        }

        processing = true;
        String code = selectedText;
        if (code != null && code.length() > MAX_CODE_CHARS) {
            code = code.substring(0, MAX_CODE_CHARS) + "\n// ...(\u4ee3\u7801\u8fc7\u957f\uff0c\u5df2\u622a\u65ad)";
        }
        final String finalCode = code;

        String prompt = buildPrompt(action, finalCode);

        // If the chat panel is available, deliver the prompt there directly \u2014 calling the LLM
        // here first and then re-sending the prompt to chat would run the same request twice.
        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (tw != null && tw.isAvailable()
                && EditorChatBridge.getInstance(project).sendPrompt(prompt)) {
            processing = false;
            hideBalloon();
            return;
        }

        showThinkingBalloon();

        executor.submit(() -> {
            String result = callLlm(prompt);
            ApplicationManager.getApplication().invokeLater(() -> {
                processing = false;
                if (editor.isDisposed()) return;

                if (result != null && !result.isEmpty()) {
                    updateBalloonContent(action + " \u7ed3\u679c\uff1a\n\n" + result);
                } else {
                    updateBalloonContent("\u8bf7\u6c42\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u6216 API \u914d\u7f6e");
                }
            });
        });
    }

    private String buildPrompt(String action, String code) {
        String langLabel = language.isEmpty() ? "" : "\uff08" + language + "\u8bed\u8a00\uff09";
        String codeBlock = "```" + language + "\n" + code + "\n```";

        return switch (action) {
            case "\u89e3\u91ca" ->
                    "\u8bf7\u7528\u4e2d\u6587\u89e3\u91ca\u4ee5\u4e0b\u6765\u81ea `" + filePath + "` \u7684\u4ee3\u7801" + langLabel + "\u7684\u529f\u80fd\u3001\u5173\u952e\u903b\u8f91\u548c\u6ce8\u610f\u70b9\uff1a\n\n" + codeBlock;
            case "\u4fee\u590d" ->
                    "\u8bf7\u68c0\u67e5\u5e76\u4fee\u590d\u4ee5\u4e0b\u4ee3\u7801" + langLabel + "\u4e2d\u7684 bug\uff0c\u8bf4\u660e\u4fee\u590d\u5185\u5bb9\uff1a\n\n" + codeBlock;
            case "\u4f18\u5316" ->
                    "\u8bf7\u91cd\u6784\u548c\u4f18\u5316\u4ee5\u4e0b\u4ee3\u7801" + langLabel + "\uff0c\u63d0\u9ad8\u53ef\u8bfb\u6027\u548c\u6027\u80fd\uff1a\n\n" + codeBlock;
            case "\u6d4b\u8bd5" ->
                    "\u8bf7\u4e3a\u4ee5\u4e0b\u4ee3\u7801" + langLabel + "\u751f\u6210\u5355\u5143\u6d4b\u8bd5\uff1a\n\n" + codeBlock;
            case "\u6ce8\u91ca" ->
                    "\u8bf7\u4e3a\u4ee5\u4e0b\u4ee3\u7801" + langLabel + "\u6dfb\u52a0\u6587\u6863\u6ce8\u91ca\uff1a\n\n" + codeBlock;
            default -> action + ":\n\n" + codeBlock;
        };
    }

    private String callLlm(String prompt) {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        String baseUrl = settings.getBaseUrl();
        String apiKey = settings.getApiKey();
        String model = settings.getModel();

        if (apiKey == null || apiKey.isEmpty() || baseUrl.isEmpty()) return null;

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("max_tokens", 2048);
        requestBody.addProperty("temperature", 0.3);
        requestBody.addProperty("stream", true);

        JsonObject streamOptions = new JsonObject();
        streamOptions.addProperty("include_usage", true);
        requestBody.add("stream_options", streamOptions);

        JsonArray messages = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", prompt);
        messages.add(msg);
        requestBody.add("messages", messages);

        String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                .build();

        Call call = httpClient.newCall(request);
        currentCall.set(call);
        try {
            StringBuilder accumulated = new StringBuilder();
            Response response = call.execute();

            if (!response.isSuccessful()) {
                response.close();
                return null;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (call.isCanceled()) break;
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;

                try {
                    JsonObject json = JsonParser.parseString(data).getAsJsonObject();
                    if (json.has("error") && !json.get("error").isJsonNull()) break;
                    JsonArray choices = json.getAsJsonArray("choices");
                    if (choices == null || choices.size() == 0) continue;
                    JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                    if (delta != null && delta.has("content") && !delta.get("content").isJsonNull()) {
                        accumulated.append(delta.get("content").getAsString());
                    }
                } catch (Exception ignored) {
                }
            }
            reader.close();
            response.close();

            return accumulated.toString().trim();
        } catch (Exception e) {
            return null;
        } finally {
            currentCall.compareAndSet(call, null);
        }
    }

    // ==================== Balloon 管理 ====================

    private void showThinkingBalloon() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (editor.isDisposed()) return;

            JBLabel label = new JBLabel("<html><body style='width:200px'>\u601d\u8003\u4e2d\u2026</body></html>",
                    new com.intellij.ui.AnimatedIcon.Default(), SwingConstants.LEFT);
            label.setBorder(JBUI.Borders.empty(8, 12));
            label.setFont(JBUI.Fonts.label(12f));
            label.setIconTextGap(JBUI.scale(8));
            label.setForeground(UIUtil.getContextHelpForeground());

            hideBalloon();

            resultBalloon = JBPopupFactory.getInstance()
                    .createBalloonBuilder(label)
                    .setFillColor(JBUI.CurrentTheme.Popup.BACKGROUND)
                    .setBorderColor(JBUI.CurrentTheme.Popup.borderColor(true))
                    .setCornerRadius(JBUI.scale(8))
                    .setShadow(true)
                    .setHideOnClickOutside(true)
                    .setHideOnKeyOutside(true)
                    .setHideOnAction(true)
                    .setRequestFocus(false)
                    .createBalloon();

            Point target = getBalloonAnchor();
            if (target != null) {
                resultBalloon.show(new RelativePoint(editor.getContentComponent(), target), Balloon.Position.below);
            }
        });
    }

    private void updateBalloonContent(String text) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (editor.isDisposed()) return;

            JTextArea textArea = new JTextArea(text);
            textArea.setEditable(false);
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);
            textArea.setFont(JBUI.Fonts.label(12f));
            textArea.setBackground(JBUI.CurrentTheme.Popup.BACKGROUND);
            textArea.setForeground(UIUtil.getLabelForeground());
            textArea.setBorder(JBUI.Borders.empty(10, 14));
            textArea.setColumns(50);
            textArea.setRows(Math.min(20, text.split("\n").length + 2));

            JBScrollPane scrollPane = new JBScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(420, 300));
            scrollPane.setBorder(JBUI.Borders.empty());

            if (resultBalloon != null && !resultBalloon.isDisposed()) {
                resultBalloon.hide();
            }

            resultBalloon = JBPopupFactory.getInstance()
                    .createBalloonBuilder(scrollPane)
                    .setFillColor(JBUI.CurrentTheme.Popup.BACKGROUND)
                    .setBorderColor(JBUI.CurrentTheme.Popup.borderColor(true))
                    .setCornerRadius(JBUI.scale(8))
                    .setShadow(true)
                    .setHideOnClickOutside(true)
                    .setHideOnKeyOutside(true)
                    .setHideOnAction(false)
                    .setRequestFocus(false)
                    .createBalloon();

            Point target = getBalloonAnchor();
            if (target != null) {
                resultBalloon.show(new RelativePoint(editor.getContentComponent(), target), Balloon.Position.below);
            }
        });
    }

    private Point getBalloonAnchor() {
        int offset = editor.getCaretModel().getOffset();
        if (offset <= 0) return null;
        com.intellij.openapi.editor.LogicalPosition logPos =
                editor.offsetToLogicalPosition(offset - 1);
        Point point = editor.visualPositionToXY(
                new com.intellij.openapi.editor.VisualPosition(logPos.line, logPos.column + 1));

        return new Point(point.x + 10, point.y + editor.getLineHeight());
    }

    private void hideBalloon() {
        if (resultBalloon != null && !resultBalloon.isDisposed()) {
            resultBalloon.hide();
            resultBalloon = null;
        }
    }
}

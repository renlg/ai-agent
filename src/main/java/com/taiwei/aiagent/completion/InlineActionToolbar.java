package com.taiwei.aiagent.completion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.RelativePoint;
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

    private JWindow toolbarWindow;
    private Balloon resultBalloon;
    private volatile boolean processing = false;

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
            if (toolbarWindow != null) {
                toolbarWindow.setVisible(false);
                toolbarWindow.dispose();
                toolbarWindow = null;
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

        toolbarWindow = new JWindow(
                SwingUtilities.getWindowAncestor(editor.getContentComponent()));
        toolbarWindow.setAlwaysOnTop(true);
        toolbarWindow.setFocusableWindowState(false);
        toolbarWindow.setFocusable(false);
        toolbarWindow.add(toolbarPanel);
        toolbarWindow.pack();

        Point location = calculateScreenPosition();
        if (location != null) {
            toolbarWindow.setLocation(location);
        }
        toolbarWindow.setVisible(true);
    }

    private JPanel createToolbarPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));

        EditorColorsScheme scheme = editor.getColorsScheme();
        Color bg = scheme.getColor(EditorColors.GUTTER_BACKGROUND);
        if (bg == null) {
            bg = UIUtil.getPanelBackground();
        }
        Color border = JBColor.border();

        panel.setBackground(bg);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border, 1),
                JBUI.Borders.empty(2, 4)
        ));
        panel.setOpaque(true);

        for (int i = 0; i < ACTION_LABELS.length; i++) {
            JButton btn = createActionButton(ACTION_LABELS[i], i);
            panel.add(btn);
            if (i < ACTION_LABELS.length - 1) {
                JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
                sep.setPreferredSize(new Dimension(1, 18));
                panel.add(sep);
            }
        }

        return panel;
    }

    private JButton createActionButton(String text, int actionIndex) {
        JButton button = new JButton(text);
        button.setFont(button.getFont().deriveFont(11f));
        button.setMargin(JBUI.insets(2, 8));
        button.setFocusable(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setContentAreaFilled(true);
                button.setBackground(new JBColor(new Color(0xDAE4ED), new Color(0x4B5662)));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.setContentAreaFilled(false);
            }
        });

        button.addActionListener(e -> {
            if (processing) return;
            executeAction(ACTION_LABELS[actionIndex]);
        });

        return button;
    }

    private Point calculateScreenPosition() {
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

        Dimension windowSize = toolbarWindow != null ? toolbarWindow.getSize() : new Dimension(300, 32);
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
        processing = true;
        String code = selectedText;
        if (code != null && code.length() > MAX_CODE_CHARS) {
            code = code.substring(0, MAX_CODE_CHARS) + "\n// ...(\u4ee3\u7801\u8fc7\u957f\uff0c\u5df2\u622a\u65ad)";
        }
        final String finalCode = code;

        String prompt = buildPrompt(action, finalCode);
        showThinkingBalloon();

        executor.submit(() -> {
            String result = callLlm(prompt);
            ApplicationManager.getApplication().invokeLater(() -> {
                processing = false;
                if (editor.isDisposed()) return;

                if (result != null && !result.isEmpty()) {
                    deliverResult(action, result, finalCode);
                } else {
                    updateBalloonContent("\u8bf7\u6c42\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u6216 API \u914d\u7f6e");
                }
            });
        });
    }

    private void deliverResult(String action, String result, String code) {
        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        boolean chatAvailable = tw != null && tw.isAvailable();

        if (chatAvailable) {
            EditorChatBridge bridge = EditorChatBridge.getInstance(project);
            String prompt = buildPrompt(action, code);
            boolean sent = bridge.sendPrompt(prompt);
            if (sent) {
                hideBalloon();
                return;
            }
        }

        String formatted = action + " \u7ed3\u679c\uff1a\n\n" + result;
        updateBalloonContent(formatted);
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

        try {
            StringBuilder accumulated = new StringBuilder();
            Response response = httpClient.newCall(request).execute();

            if (!response.isSuccessful()) {
                response.close();
                return null;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
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
        }
    }

    // ==================== Balloon 管理 ====================

    private void showThinkingBalloon() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (editor.isDisposed()) return;

            JBLabel label = new JBLabel("<html><body style='width:200px'>\u601d\u8003\u4e2d...</body></html>");
            label.setBorder(JBUI.Borders.empty(6, 10));
            label.setForeground(JBColor.GRAY);

            hideBalloon();

            resultBalloon = JBPopupFactory.getInstance()
                    .createBalloonBuilder(label)
                    .setFillColor(UIUtil.getPanelBackground())
                    .setBorderColor(JBColor.border())
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
            textArea.setFont(UIUtil.getLabelFont());
            textArea.setBackground(UIUtil.getPanelBackground());
            textArea.setBorder(JBUI.Borders.empty(8, 12));
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
                    .setFillColor(UIUtil.getPanelBackground())
                    .setBorderColor(JBColor.border())
                    .setHideOnClickOutside(true)
                    .setHideOnKeyOutside(true)
                    .setHideOnAction(false)
                    .setRequestFocus(false)
                    .setResizable(true)
                    .setMovable(true)
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

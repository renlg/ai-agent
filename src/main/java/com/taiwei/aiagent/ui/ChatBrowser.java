package com.taiwei.aiagent.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * JCEF 浏览器组件
 * 加载 HTML 前端页面，并通过 ChatMessageHandler 处理 JS ↔ Java 通信
 */
public class ChatBrowser implements Disposable {

    private static final Logger LOG = Logger.getInstance(ChatBrowser.class);

    private final JBCefBrowser jbCefBrowser;
    private final ChatMessageHandler messageHandler;
    private final Project project;

    public ChatBrowser(@NotNull Project project) {
        this.project = project;

        // 创建 JCEF 浏览器
        this.jbCefBrowser = new JBCefBrowser();

        // 创建消息处理器
        this.messageHandler = new ChatMessageHandler(project, jbCefBrowser);

        // 加载 HTML 页面
        loadHtmlPage();
    }

    /**
     * 加载前端 HTML 页面
     * 将所有 CSS/JS 资源内联到 HTML 中，通过 loadHTML 加载
     * （JCEF 无法通过 loadURL 正确解析 classpath 中的相对路径资源）
     */
    private void loadHtmlPage() {
        try {
            // 读取所有资源
            String html = readResource("html/index.html");
            String css = readResource("html/css/style.css");
            String markdownJs = readResource("html/js/markdown.js");
            String chatJs = readResource("html/js/chat.js");
            String appJs = readResource("html/js/app.js");

            // 内联 CSS
            html = html.replace(
                    "<link rel=\"stylesheet\" href=\"css/style.css\">",
                    "<style>" + "\n" + css + "\n" + "</style>"
            );

            // 内联 JS
            html = html.replace(
                    "<script src=\"js/markdown.js\">" + "</script>",
                    "<script>" + "\n" + markdownJs + "\n" + "</script>"
            );
            html = html.replace(
                    "<script src=\"js/app.js\">" + "</script>",
                    "<script>" + "\n" + appJs + "\n" + "</script>"
            );
            html = html.replace(
                    "<script src=\"js/chat.js\">" + "</script>",
                    "<script>" + "\n" + chatJs + "\n" + "</script>"
            );

            LOG.info("加载内联 HTML 页面");
            jbCefBrowser.loadHTML(html);

        } catch (Exception e) {
            LOG.error("加载 HTML 页面失败", e);
            loadFallbackHtml();
        }
    }

    /**
     * 从 classpath 读取资源文件内容
     */
    private String readResource(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                LOG.warn("资源未找到: " + path);
                return "";
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            LOG.warn("读取资源失败: " + path, e);
            return "";
        }
    }

    /**
     * 加载备用 HTML（当资源文件读取失败时）
     */
    private void loadFallbackHtml() {
        String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
                + "<style>body{font-family:sans-serif;display:flex;align-items:center;"
                + "justify-content:center;height:100vh;}"
                + ".error{text-align:center;padding:20px;border:1px solid #ddd;"
                + "border-radius:8px;max-width:400px;}"
                + ".error h2{color:#e74c3c;}" + "</style></head>"
                + "<body><div class='error'><h2>页面加载失败</h2>"
                + "<p>无法加载太微前端页面资源。</p>"
                + "<p>请查看 IDE 日志获取详细信息。</p></div></body></html>";
        jbCefBrowser.loadHTML(html);
    }

    /**
     * 获取 Swing 组件（用于嵌入到面板中）
     */
    public JComponent getComponent() {
        return jbCefBrowser.getComponent();
    }

    public JBCefBrowser getJbCefBrowser() {
        return jbCefBrowser;
    }

    public ChatMessageHandler getMessageHandler() {
        return messageHandler;
    }

    @Override
    public void dispose() {
        messageHandler.dispose();
        jbCefBrowser.dispose();
    }
}

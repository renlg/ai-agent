package com.taiwei.aiagent.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.io.File;
import java.net.URL;

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
     */
    private void loadHtmlPage() {
        // 获取 html/index.html 的路径
        URL htmlUrl = getClass().getClassLoader().getResource("html/index.html");

        if (htmlUrl != null) {
            String url = htmlUrl.toExternalForm();
            LOG.info("加载页面: " + url);
            jbCefBrowser.loadURL(url);
        } else {
            // 如果找不到资源，显示错误信息
            LOG.warn("未找到 html/index.html 资源，显示内联页面");
            loadInlineHtml();
        }
    }

    /**
     * 加载内联 HTML（备用方案）
     */
    private void loadInlineHtml() {
        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                            background: var(--bg, #ffffff);
                            color: var(--text, #333333);
                            margin: 0;
                            padding: 20px;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            height: 100vh;
                        }
                        .error {
                            text-align: center;
                            padding: 20px;
                            border: 1px solid #ddd;
                            border-radius: 8px;
                            max-width: 400px;
                        }
                        .error h2 { color: #e74c3c; }
                    </style>
                </head>
                <body>
                    <div class="error">
                        <h2>页面加载失败</h2>
                        <p>无法找到前端页面资源。</p>
                        <p>请确保 html/index.html 存在于 resources 目录中。</p>
                    </div>
                </body>
                </html>
                """;
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

package com.taiwei.aiagent.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;

/**
 * AI 聊天面板 UI
 * 使用 JCEF 浏览器加载 HTML 前端页面
 */
public class ChatPanel extends JPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(ChatPanel.class);

    private ChatBrowser chatBrowser;

    public ChatPanel(com.intellij.openapi.project.Project project) {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty());

        try {
            // 创建 JCEF 浏览器组件
            chatBrowser = new ChatBrowser(project);
            Disposer.register(this, chatBrowser);

            // 添加到面板
            add(chatBrowser.getComponent(), BorderLayout.CENTER);

            LOG.info("ChatPanel 初始化完成（JCEF 模式）");

        } catch (Exception e) {
            LOG.error("JCEF 初始化失败，降级为 Swing 模式", e);
            initFallbackUI();
        }
    }

    /**
     * 降级 UI（当 JCEF 不可用时使用）
     */
    private void initFallbackUI() {
        JLabel label = new JLabel("<html><center><h3>JCEF 不可用</h3>"
                + "<p>当前 IDE 环境不支持内嵌浏览器。</p>"
                + "<p>请使用支持 JCEF 的 IDE 版本。</p></center></html>");
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setForeground(JBColor.RED);
        add(label, BorderLayout.CENTER);
    }

    @Override
    public void dispose() {
        // Disposer 会自动清理 chatBrowser
    }
}

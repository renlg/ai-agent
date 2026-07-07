package com.taiwei.aiagent.window;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.taiwei.aiagent.ui.ChatPanel;
import org.jetbrains.annotations.NotNull;

/**
 * AI Agent 工具窗口工厂
 * 在 IDEA 右侧注册 AI 聊天面板
 */
public class ChatWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // 创建聊天面板
        ChatPanel chatPanel = new ChatPanel(project);

        // 获取 ContentFactory（兼容 IDEA 2024.1+ 新 API）
        ContentFactory contentFactory = ContentFactory.getInstance();

        // 创建 Content 并添加到 ToolWindow
        Content content = contentFactory.createContent(chatPanel, "Chat", false);
        toolWindow.getContentManager().addContent(content);
    }
}

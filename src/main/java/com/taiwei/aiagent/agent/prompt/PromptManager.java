package com.taiwei.aiagent.agent.prompt;

import com.intellij.openapi.project.Project;

import java.util.HashMap;
import java.util.Map;

/**
 * Prompt 模板管理器
 * 生成系统提示词，告知 Agent 其身份和可用工具
 */
public class PromptManager {

    private final Project project;
    private final TemplateEngine templateEngine;

    public PromptManager(Project project) {
        this.project = project;
        this.templateEngine = new TemplateEngine();
    }

    /**
     * 生成系统提示词
     */
    public String buildSystemPrompt() {
        String projectName = project.getName();
        String basePath = project.getBasePath();

        Map<String, Object> context = new HashMap<>();
        context.put("projectName", projectName);
        context.put("basePath", basePath != null ? basePath : "\u672a\u77e5");

        return templateEngine.render("templates/system_prompt.vm", context);
    }
}

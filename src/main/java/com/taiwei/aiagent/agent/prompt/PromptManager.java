package com.taiwei.aiagent.agent.prompt;

import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.settings.AiAgentSettings;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

        String systemPrompt = templateEngine.render("templates/system_prompt.vm", context);
        return systemPrompt + "\n" + buildEnvBlock();
    }

    private String buildEnvBlock() {
        String basePath = project.getBasePath();
        String workingDir = basePath != null ? basePath : "未知";

        boolean isGitRepo = basePath != null && new File(basePath, ".git").exists();

        String osName = System.getProperty("os.name", "").toLowerCase();
        String platform;
        if (osName.contains("mac") || osName.contains("darwin")) {
            platform = "macos";
        } else if (osName.contains("linux")) {
            platform = "linux";
        } else if (osName.contains("win")) {
            platform = "windows";
        } else {
            platform = osName;
        }

        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        String model;
        try {
            model = AiAgentSettings.getInstance().getModel();
        } catch (Exception e) {
            model = "未知";
        }

        return "<env>\n"
                + "  Working directory: " + workingDir + "\n"
                + "  Is directory a git repo: " + (isGitRepo ? "Yes" : "No") + "\n"
                + "  Platform: " + platform + "\n"
                + "  Today's date: " + today + "\n"
                + "  Model: " + model + "\n"
                + "</env>";
    }
}

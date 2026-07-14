package com.taiwei.aiagent.agent.prompt;

import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.agent.AgentMode;
import com.taiwei.aiagent.settings.AiAgentSettings;
import com.taiwei.aiagent.skill.SkillManager;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Prompt 模板管理器
 * 使用 Velocity 模板引擎渲染系统提示词
 */
public class PromptManager {

    private final Project project;
    private final VelocityEngine velocityEngine;
    private final SkillManager skillManager;

    public PromptManager(Project project) {
        this.project = project;
        this.velocityEngine = createVelocityEngine();
        this.skillManager = SkillManager.getInstance(project);
    }

    private VelocityEngine createVelocityEngine() {
        Properties props = new Properties();
        props.setProperty("resource.loaders", "string");
        props.setProperty("resource.loader.string.class", "org.apache.velocity.runtime.resource.loader.StringResourceLoader");
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(PromptManager.class.getClassLoader());
            VelocityEngine engine = new VelocityEngine();
            engine.init(props);
            return engine;
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    /**
     * 生成系统提示词
     *
     * @param mode 当前 Agent 模式（Plan/Build），决定提示词中工具能力描述与行为约束
     */
    public String buildSystemPrompt(AgentMode mode) {
        VelocityContext context = new VelocityContext();

        String basePath = project.getBasePath();
        String workingDir = basePath != null ? basePath : "未知";
        context.put("workingDir", workingDir);

        boolean isGitRepo = basePath != null && new File(basePath, ".git").exists();
        context.put("isGitRepo", isGitRepo ? "Yes" : "No");

        context.put("platform", detectPlatform());
        context.put("today", LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));

        String model;
        try {
            model = AiAgentSettings.getInstance().getModel();
        } catch (Exception e) {
            model = "未知";
        }
        context.put("model", model != null ? model : "未知");

        boolean isPlanMode = mode == AgentMode.PLAN;
        context.put("isPlanMode", isPlanMode);
        context.put("modeLabel", isPlanMode ? "Plan（只读分析）" : "Build（正常）");

        String skillsContext = buildSkillsContext();
        if (skillsContext != null && !skillsContext.isEmpty()) {
            context.put("skills", skillsContext);
        }

        String templateContent = loadTemplateContent("templates/system_prompt.vm");
        StringWriter writer = new StringWriter();
        velocityEngine.evaluate(context, writer, "system_prompt", templateContent);
        return writer.toString();
    }

    /**
     * 生成 /init 命令用于生成 AGENTS.md 的提示词
     *
     * @param scanSummary      项目结构扫描结果（目录树、模块、关键配置文件内容）
     * @param existingAgentsMd 已存在的 AGENTS.md 内容（不存在则为 null）
     */
    public String buildInitPrompt(String scanSummary, String existingAgentsMd) {
        VelocityContext context = new VelocityContext();

        String basePath = project.getBasePath();
        context.put("workingDir", basePath != null ? basePath : "未知");
        context.put("platform", detectPlatform());
        context.put("today", LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        context.put("scanSummary", scanSummary != null ? scanSummary : "");

        boolean hasExisting = existingAgentsMd != null && !existingAgentsMd.isEmpty();
        context.put("hasExisting", hasExisting);
        context.put("existingContent", hasExisting ? existingAgentsMd : "");

        String templateContent = loadTemplateContent("templates/init_prompt.vm");
        StringWriter writer = new StringWriter();
        velocityEngine.evaluate(context, writer, "init_prompt", templateContent);
        return writer.toString();
    }

    /**
     * 构建技能上下文，用于注入系统提示词（仅 name/description/tags，完整内容按需加载）
     */
    public String buildSkillsContext() {
        return skillManager.buildSummaryContext();
    }

    private String loadTemplateContent(String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new RuntimeException("Template not found: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load template: " + resourcePath, e);
        }
    }

    private String detectPlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("mac") || osName.contains("darwin")) {
            return "macos";
        } else if (osName.contains("linux")) {
            return "linux";
        } else if (osName.contains("win")) {
            return "windows";
        } else {
            return osName;
        }
    }
}

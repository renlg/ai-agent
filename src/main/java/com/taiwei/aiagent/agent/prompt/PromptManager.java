package com.taiwei.aiagent.agent.prompt;

import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.settings.AiAgentSettings;
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

    public PromptManager(Project project) {
        this.project = project;
        this.velocityEngine = createVelocityEngine();
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
     */
    public String buildSystemPrompt() {
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

        String templateContent = loadTemplateContent();
        StringWriter writer = new StringWriter();
        velocityEngine.evaluate(context, writer, "system_prompt", templateContent);
        return writer.toString();
    }

    private String loadTemplateContent() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("templates/system_prompt.vm")) {
            if (is == null) {
                throw new RuntimeException("Template not found: templates/system_prompt.vm");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load template", e);
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

package com.taiwei.aiagent.agent.prompt;

import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.settings.AiAgentSettings;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

import java.io.File;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

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
        props.setProperty("resource.loaders", "classpath");
        props.setProperty("resource.loader.classpath.class", ClasspathResourceLoader.class.getName());
        VelocityEngine engine = new VelocityEngine();
        engine.init(props);
        return engine;
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

        Template template = velocityEngine.getTemplate("templates/system_prompt.vm", "UTF-8");
        StringWriter writer = new StringWriter();
        template.merge(context, writer);
        return writer.toString();
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

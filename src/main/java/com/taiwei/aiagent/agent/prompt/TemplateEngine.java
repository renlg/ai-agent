package com.taiwei.aiagent.agent.prompt;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

import java.io.StringWriter;
import java.util.Map;

public class TemplateEngine {

    private final VelocityEngine engine;

    public TemplateEngine() {
        engine = new VelocityEngine();
        engine.setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath");
        engine.setProperty("resource.loader.classpath.class", ClasspathResourceLoader.class.getName());
        engine.setProperty(RuntimeConstants.INPUT_ENCODING, "UTF-8");
        engine.init();
    }

    public String render(String templatePath, Map<String, Object> context) {
        VelocityContext velocityContext = new VelocityContext();
        if (context != null) {
            context.forEach(velocityContext::put);
        }
        StringWriter writer = new StringWriter();
        engine.getTemplate(templatePath).merge(velocityContext, writer);
        return writer.toString();
    }
}

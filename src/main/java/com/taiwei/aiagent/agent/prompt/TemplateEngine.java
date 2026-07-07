package com.taiwei.aiagent.agent.prompt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TemplateEngine {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{(\\w+)}");

    public String render(String templatePath, Map<String, Object> context) {
        String template = loadTemplate(templatePath);
        return replaceVariables(template, context);
    }

    private String loadTemplate(String templatePath) {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(templatePath);
        if (inputStream == null) {
            throw new IllegalArgumentException("Template not found: " + templatePath);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read template: " + templatePath, e);
        }
    }

    private String replaceVariables(String template, Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return template;
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = context.get(key);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value != null ? value.toString() : ""));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}

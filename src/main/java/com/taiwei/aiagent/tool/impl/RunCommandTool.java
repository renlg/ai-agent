package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.tool.Tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 执行终端命令工具
 * Agent 可以通过此工具在项目目录下执行命令
 */
public class RunCommandTool implements Tool {

    private final Project project;

    /**
     * 命令执行超时（秒）
     */
    private static final int DEFAULT_TIMEOUT = 30;
    private static final int MAX_TIMEOUT = 120;

    public RunCommandTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "run_command";
    }

    @Override
    public String getDescription() {
        return "在项目根目录下执行终端命令并返回输出结果。注意：命令将在项目目录下执行，请确保命令安全。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "command": {
                      "type": "string",
                      "description": "要执行的终端命令（如 'ls -la'、'cat build.gradle'）"
                    },
                    "timeout": {
                      "type": "integer",
                      "description": "超时时间（秒，默认30，最大120）"
                    }
                  },
                  "required": ["command"]
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String command = args.get("command").getAsString();
            int timeout = args.has("timeout") ? args.get("timeout").getAsInt() : DEFAULT_TIMEOUT;
            timeout = Math.min(timeout, MAX_TIMEOUT);

            // 安全检查：禁止危险命令
            if (isDangerousCommand(command)) {
                return "安全限制: 不允许执行此命令。禁止执行 rm -rf /、格式化磁盘等危险操作。";
            }

            String basePath = project.getBasePath();
            if (basePath == null) {
                return "错误: 无法获取项目路径";
            }

            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
            pb.directory(new java.io.File(basePath));
            pb.redirectErrorStream(true);
            pb.environment().put("LANG", "en_US.UTF-8");

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null) {
                    if (lineCount >= 200) {
                        output.append("\n... [输出过多，已截断，共超过 200 行]");
                        break;
                    }
                    output.append(line).append("\n");
                    lineCount++;
                }
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "命令执行超时（" + timeout + "秒），已强制终止。\n\n部分输出:\n" + output;
            }

            int exitCode = process.exitValue();
            String result = output.toString();

            if (result.length() > 30000) {
                result = result.substring(0, 30000) + "\n... [输出过长，已截断]";
            }

            return String.format("退出码: %d\n\n输出:\n%s", exitCode, result);

        } catch (Exception e) {
            return "执行命令失败: " + e.getMessage();
        }
    }

    /**
     * 简单安全检查，禁止极端危险命令
     */
    private boolean isDangerousCommand(String command) {
        String lower = command.toLowerCase().trim();
        return lower.contains("rm -rf /") ||
               lower.contains("mkfs") ||
               lower.contains("dd if=/dev/zero") ||
               lower.contains(":(){:|:&};:");
    }
}

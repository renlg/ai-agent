package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import org.jetbrains.plugins.terminal.TerminalView;
import com.taiwei.aiagent.tool.Tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 执行终端命令工具
 * 使用 IDEA 内置 Terminal 组件执行命令，用户可以在终端中实时看到命令和输出
 */
public class RunCommandTool implements Tool {

    private static final Logger LOG = Logger.getInstance(RunCommandTool.class);

    private final Project project;

    /**
     * 停止标志，用于支持用户主动停止执行
     */
    private volatile boolean stopped = false;

    /**
     * 命令执行超时（秒）
     */
    private static final int DEFAULT_TIMEOUT = 120;
    private static final int MAX_TIMEOUT = 300;

    /**
     * 超时后仍在后台运行的进程，等待多久后强制清理临时目录（防止句柄/磁盘泄漏）
     */
    private static final long ORPHAN_CLEANUP_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);

    public RunCommandTool(Project project) {
        this.project = project;
    }

    /**
     * 停止当前正在执行的命令
     */
    public void stop() {
        this.stopped = true;
    }

    @Override
    public String getName() {
        return "run_command";
    }

    @Override
    public String getDescription() {
        return "在项目根目录下通过 IDEA 终端执行命令并返回输出结果。命令会在 IDEA 终端中可见执行。";
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
                      "description": "超时时间（秒，默认10，最大300）"
                    }
                  },
                  "required": ["command"]
                }
                """;
    }

    @Override
    public boolean isMutating() {
        return true;
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

            // 创建临时文件用于捕获输出和完成信号
            String uniqueId = UUID.randomUUID().toString().substring(0, 8);
            Path tempDir = Files.createTempDirectory("taiwei-cmd-");
            Path outputFile = tempDir.resolve("output-" + uniqueId + ".txt");
            Path doneFile = tempDir.resolve("done-" + uniqueId);

            // 创建包装脚本：在终端中可见执行，同时捕获输出到文件
            Path scriptFile = tempDir.resolve("run-" + uniqueId + ".sh");
            // Single quotes prevent ALL shell expansion ($, backticks, globbing) in the display line — prevents command injection
            String escapedCmd = command.replace("'", "'\\''");
            String scriptContent = "#!/bin/sh\n"
                    + "cd " + shellEscape(basePath) + " || exit 1\n"
                    + "printf '[%s] $ %s\\n' \"$(pwd)\" '" + escapedCmd + "'\n"
                    + "(" + command + ") 2>&1 | tee " + shellEscape(outputFile.toString()) + "\n"
                    + "echo $? > " + shellEscape(doneFile.toString()) + "\n";
            Files.writeString(scriptFile, scriptContent, StandardCharsets.UTF_8);
            scriptFile.toFile().setExecutable(true);

            // 在 IDEA 终端中执行命令（需要在 EDT 上执行）
            CountDownLatch terminalLatch = new CountDownLatch(1);
            AtomicReference<String> terminalError = new AtomicReference<>();

            ApplicationManager.getApplication().invokeAndWait(() -> {
                try {
                    TerminalView terminalView = TerminalView.getInstance(project);

                    // 在项目目录下创建终端 Tab，并执行包装脚本
                    ShellTerminalWidget widget = terminalView.createLocalShellWidget(
                            basePath,
                            "TaiWei: " + truncate(command, 30)
                    );
                    widget.executeCommand("sh " + shellEscape(scriptFile.toString()));
                } catch (Exception e) {
                    LOG.error("创建终端失败", e);
                    terminalError.set("创建终端失败: " + e.getMessage());
                } finally {
                    terminalLatch.countDown();
                }
            });

            terminalLatch.await(10, TimeUnit.SECONDS);

            if (terminalError.get() != null) {
                cleanupTempFiles(tempDir, scriptFile, doneFile, outputFile);
                return terminalError.get();
            }

            // 轮询等待 done 标记文件出现（命令执行完成）
            long startTime = System.currentTimeMillis();
            long timeoutMs = timeout * 1000L;

            while (!doneFile.toFile().exists()) {
                // 检查是否被用户停止
                if (stopped) {
                    cleanupTempFiles(tempDir, scriptFile, doneFile, outputFile);
                    return "[命令已被用户停止]\n\n已捕获的部分输出:\n" + readPartialOutput(outputFile);
                }
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    // 超时时不杀进程，让终端继续运行，只返回已捕获的部分输出
                    String partialOutput = readPartialOutput(outputFile);
                    // 不立即删除临时文件（进程可能仍在写入），但安排后台清理，避免临时目录永久泄漏
                    scheduleOrphanCleanup(tempDir, scriptFile, doneFile, outputFile);
                    if (partialOutput.isEmpty() || partialOutput.equals("(无输出)")) {
                        return "命令执行超过 " + timeout + " 秒仍未完成，进程仍在终端中运行。\n\n(暂无输出，请切换到终端查看)";
                    }
                    return "命令执行超过 " + timeout + " 秒仍未完成，进程仍在终端中运行。\n\n已捕获的部分输出:\n" + partialOutput;
                }
                Thread.sleep(500);
            }

            // 短暂等待 tee 刷新完成
            Thread.sleep(200);

            // 读取退出码
            String exitCodeStr = Files.readString(doneFile, StandardCharsets.UTF_8).trim();
            int exitCode;
            try {
                exitCode = Integer.parseInt(exitCodeStr);
            } catch (NumberFormatException e) {
                exitCode = -1;
            }

            // 读取输出
            String output = "";
            if (Files.exists(outputFile)) {
                output = Files.readString(outputFile, StandardCharsets.UTF_8);
                if (output.length() > 30000) {
                    output = output.substring(0, 30000) + "\n... [输出过长，已截断]";
                }
            }

            // 清理临时文件
            cleanupTempFiles(tempDir, scriptFile, doneFile, outputFile);

            return String.format("退出码: %d\n\n输出:\n%s", exitCode, output);

        } catch (Exception e) {
            return "执行命令失败: " + e.getMessage();
        }
    }

    /**
     * 读取部分输出（用于超时场景）
     */
    private String readPartialOutput(Path outputFile) {
        try {
            if (Files.exists(outputFile)) {
                String output = Files.readString(outputFile, StandardCharsets.UTF_8);
                if (output.length() > 5000) {
                    return output.substring(0, 5000) + "\n...";
                }
                return output;
            }
        } catch (Exception ignored) {
        }
        return "(无输出)";
    }

    /**
     * 命令超时后，进程可能仍在终端中运行并持续写入 outputFile，因此不能立即删除临时目录。
     * 在后台线程中轮询等待 doneFile 出现（进程结束），或达到最大等待时间后强制清理，
     * 避免每次超时都留下一个永不删除的临时目录（磁盘/句柄泄漏）。
     */
    private void scheduleOrphanCleanup(Path tempDir, Path scriptFile, Path doneFile, Path outputFile) {
        Thread cleanupThread = new Thread(() -> {
            long deadline = System.currentTimeMillis() + ORPHAN_CLEANUP_TIMEOUT_MS;
            try {
                while (!doneFile.toFile().exists() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(5000);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            cleanupTempFiles(tempDir, scriptFile, doneFile, outputFile);
        }, "taiwei-cmd-orphan-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    /**
     * 清理临时文件
     */
    private void cleanupTempFiles(Path tempDir, Path... files) {
        for (Path f : files) {
            try {
                Files.deleteIfExists(f);
            } catch (Exception ignored) {
            }
        }
        try {
            Files.deleteIfExists(tempDir);
        } catch (Exception ignored) {
        }
    }

    /**
     * Shell 路径转义
     */
    private String shellEscape(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    /**
     * 截断字符串
     */
    private String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
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

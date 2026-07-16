package com.taiwei.aiagent.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.llm.LlmClient;
import com.taiwei.aiagent.llm.LlmResponse;
import com.taiwei.aiagent.llm.LlmStreamListener;
import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.settings.AiAgentSettings;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolRegistry;
import com.taiwei.aiagent.tool.impl.RunCommandTool;
import com.taiwei.aiagent.util.TokenCounter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Agent 核心服务
 * 实现 Agent 循环：接收用户消息 → 调用 LLM → 执行工具 → 循环直到得到最终回答
 * 支持多会话管理
 * 支持危险命令审批流程和 IntelliJ Terminal API 执行
 */
public class AgentService {

    private static final Logger LOG = Logger.getInstance(AgentService.class);

    private final SessionManager sessionManager;
    private final ApprovalManager approvalManager = new ApprovalManager();
    private final Project project;
    private volatile boolean stopped = false;
    private volatile LlmClient activeLlmClient = null;
    private final ExecutorService toolExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-agent-tool-" + r.hashCode());
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, RunCommandTool> activeRunCommandTools = new ConcurrentHashMap<>();

    /**
     * 命令审批管理器
     */
    public static class ApprovalManager {
        private final Map<String, CommandApproval> pendingApprovals = new ConcurrentHashMap<>();

        public static class CommandApproval {
            final CountDownLatch latch = new CountDownLatch(1);
            volatile boolean approved = false;
            volatile String result = null;
        }

        public CommandApproval register(String toolCallId) {
            CommandApproval ca = new CommandApproval();
            pendingApprovals.put(toolCallId, ca);
            return ca;
        }

        public void approve(String toolCallId) {
            CommandApproval ca = pendingApprovals.get(toolCallId);
            if (ca != null) {
                ca.approved = true;
            }
        }

        public void setResult(String toolCallId, String result) {
            CommandApproval ca = pendingApprovals.remove(toolCallId);
            if (ca != null) {
                ca.approved = true;
                ca.result = result;
                ca.latch.countDown();
            }
        }

        public void reject(String toolCallId) {
            CommandApproval ca = pendingApprovals.remove(toolCallId);
            if (ca != null) {
                ca.approved = false;
                ca.latch.countDown();
            }
        }
    }

    public ApprovalManager getApprovalManager() {
        return approvalManager;
    }

    /**
     * Agent 事件监听器
     */
    public interface AgentListener {
        /** LLM 开始思考 */
        void onThinking();

        /** 收到文本内容片段 */
        void onContent(String content);

        /** 开始调用工具 */
        void onToolCallStart(String toolCallId, String toolName, String arguments);

        /** 工具调用完成 */
        void onToolCallEnd(String toolCallId, String toolName, String result);

        /** 命令需要审批（危险命令） */
        default void onCommandApproval(String toolCallId, String command, boolean isDangerous) {}

        /** 命令执行进度更新 */
        default void onCommandProgress(String toolCallId, String status) {}

        /** 命令执行结果 */
        default void onCommandResult(String toolCallId, String result) {}

        /** Agent 模式已切换（"plan" / "build"） */
        default void onModeChanged(String mode) {}

        /** Agent 完成回答 */
        void onComplete(String fullResponse);

        /** Token 使用统计（多次迭代累加后在 onComplete 前调用） */
        default void onUsage(LlmResponse.Usage usage) {}

        /** 发生错误 */
        void onError(String error);
    }

    /**
     * 压缩事件监听器
     */
    public interface CompressionListener {
        void onCompressed(int beforeTokens, int afterTokens);
    }

    private volatile CompressionListener compressionListener;

    public void setCompressionListener(CompressionListener listener) {
        this.compressionListener = listener;
    }

    public AgentService(Project project) {
        this.project = project;
        this.sessionManager = new SessionManager(project);
        this.sessionManager.loadFromDisk();
    }

    /**
     * 获取当前活跃会话的上下文（向后兼容）
     */
    public AgentContext getContext() {
        return sessionManager.getActiveContext();
    }

    /**
     * 获取 SessionManager
     */
    public SessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * 创建新会话
     */
    public String createSession() {
        return sessionManager.createSession();
    }

    /**
     * 切换到指定会话
     */
    public void switchSession(String sessionId) {
        sessionManager.switchSession(sessionId);
    }

    /**
     * 获取当前活跃会话 ID
     */
    public String getActiveSessionId() {
        return sessionManager.getActiveSessionId();
    }

    /**
     * 列出所有会话
     */
    public List<SessionManager.SessionInfo> listSessions() {
        return sessionManager.listSessions();
    }

    /**
     * 删除指定会话
     */
    public void deleteSession(String sessionId) {
        sessionManager.deleteSession(sessionId);
    }

    /**
     * 发送用户消息并执行 Agent 循环（使用当前活跃会话）
     */
    public void sendMessage(String userMessage, AgentListener listener) {
        sendMessage(null, userMessage, listener);
    }

    /**
     * 发送用户消息并执行 Agent 循环
     * 拦截 /plan、/build、/init 斜杠命令，不进入正常的 LLM 对话流程
     */
    public void sendMessage(String sessionId, String userMessage, AgentListener listener) {
        sendMessage(sessionId, userMessage, null, listener);
    }

    /**
     * 发送带图片的用户消息并执行 Agent 循环（视觉输入）
     */
    public void sendMessage(String sessionId, String userMessage, List<ChatMessage.ImageContent> images, AgentListener listener) {
        AgentContext ctx;
        if (sessionId != null && !sessionId.isEmpty()) {
            sessionManager.getOrCreateSession(sessionId);
            ctx = sessionManager.getContext(sessionId);
        } else {
            ctx = sessionManager.getActiveContext();
        }

        String trimmed = userMessage == null ? "" : userMessage.trim();

        if ("/plan".equalsIgnoreCase(trimmed) || "/build".equalsIgnoreCase(trimmed)) {
            handleModeSwitch(ctx, "/plan".equalsIgnoreCase(trimmed) ? AgentMode.PLAN : AgentMode.BUILD, listener);
            sessionManager.saveState();
            return;
        }

        if ("/init".equalsIgnoreCase(trimmed)) {
            handleInitCommand(ctx, listener);
            sessionManager.saveState();
            return;
        }

        // 添加用户消息到对话历史
        ctx.getConversation().addUserMessage(userMessage, images);

        // 基于当前用户消息检索相关长期记忆，重建系统提示词（就地替换，不影响已有对话历史）
        ctx.getConversation().updateSystemPrompt(ctx.getPromptManager().buildSystemPrompt(ctx.getMode(), userMessage));

        stopped = false;
        activeLlmClient = ctx.getLlmClient();

        listener.onThinking();

        // 启动 Agent 循环
        executeAgentLoop(ctx, listener);

        // 消息处理完成后持久化
        activeLlmClient = null;
        sessionManager.saveState();
    }

    /**
     * 处理 /plan、/build 模式切换命令：不发送给 LLM，直接切换模式并提示用户
     */
    private void handleModeSwitch(AgentContext ctx, AgentMode newMode, AgentListener listener) {
        ctx.setMode(newMode);
        listener.onModeChanged(newMode.toJsValue());
        String msg = newMode == AgentMode.PLAN
                ? "已切换到 **Plan 模式**：只读分析，禁止修改文件或执行命令，最终以 Markdown 输出实施计划。"
                : "已切换到 **Build 模式**：可正常读写文件、执行命令。";
        listener.onThinking();
        listener.onContent(msg);
        listener.onComplete(msg);
    }

    /**
     * 处理 /init 命令：扫描项目结构，交给 LLM 生成 AGENTS.md 内容，并直接写入项目根目录
     */
    private void handleInitCommand(AgentContext ctx, AgentListener listener) {
        listener.onThinking();
        try {
            String basePath = project.getBasePath();
            if (basePath == null) {
                listener.onError("无法获取项目根目录，/init 已取消");
                return;
            }

            java.nio.file.Path agentsMdPath = java.nio.file.Paths.get(basePath, "AGENTS.md");
            String existing = null;
            if (java.nio.file.Files.exists(agentsMdPath)) {
                try {
                    existing = java.nio.file.Files.readString(agentsMdPath, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    LOG.warn("读取已有 AGENTS.md 失败", e);
                    listener.onContent("\n\n⚠️ 读取已有 AGENTS.md 失败: " + e.getMessage());
                }
            }

            String scanSummary = com.taiwei.aiagent.agent.init.ProjectScanner.scan(project);

            String initPrompt = ctx.getPromptManager().buildInitPrompt(scanSummary, existing);

            List<ChatMessage> request = new ArrayList<>();
            request.add(ChatMessage.system("你是一名资深软件工程师，严格按照用户指示输出 AGENTS.md 正文内容，不要添加任何多余的解释或前后缀。"));
            request.add(ChatMessage.user(initPrompt));

            stopped = false;
            LlmClient llmClient = ctx.getLlmClient();
            activeLlmClient = llmClient;

            LlmResponse response = llmClient.chat(request, null);
            activeLlmClient = null;

            if (response == null || !response.isSuccess() || response.getContent() == null || response.getContent().isEmpty()) {
                String err = response != null ? response.getErrorMessage() : "LLM 未返回内容";
                listener.onError("生成 AGENTS.md 失败: " + err);
                return;
            }

            String agentsMdContent = response.getContent().trim();
            java.nio.file.Files.writeString(agentsMdPath, agentsMdContent, java.nio.charset.StandardCharsets.UTF_8);

            ctx.getConversation().addUserMessage("/init");
            String summary = (existing != null ? "已更新" : "已生成") + " AGENTS.md（" + agentsMdPath + "）\n\n---\n\n" + agentsMdContent;
            ctx.getConversation().addAssistantMessage(summary);

            if (response.getUsage() != null) {
                listener.onUsage(response.getUsage());
            }
            listener.onContent(summary);
            listener.onComplete(summary);
        } catch (Exception e) {
            LOG.error("/init 执行失败", e);
            listener.onError("/init 执行失败: " + e.getMessage());
        }
    }

    /**
     * 检查命令是否匹配危险命令模式
     */
    public boolean isDangerousCommand(String command) {
        List<String> patterns = AiAgentSettings.getInstance().getDangerousCommands();
        String lower = command.toLowerCase().trim();
        for (String pattern : patterns) {
            if (lower.contains(pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 停止当前正在进行的生成
     * 同时停止 LLM 调用和正在执行的工具调用
     */
    public void stopGeneration() {
        stopped = true;
        LlmClient client = activeLlmClient;
        if (client != null) {
            client.cancel();
        }
        // 停止所有正在执行的命令工具
        for (RunCommandTool tool : activeRunCommandTools.values()) {
            tool.stop();
        }
        activeRunCommandTools.clear();
    }

    /**
     * 检查是否已停止
     */
    public boolean isStopped() {
        return stopped;
    }

    /**
     * Agent 核心循环（流式）
     * 使用 SSE 流式调用 LLM，实时推送内容片段到前端
     * 工具调用在流结束后统一处理，然后继续循环
     * run_command 工具使用 IntelliJ Terminal API + 危险命令审批
     */
    private void executeAgentLoop(AgentContext context, AgentListener listener) {
        LlmClient llmClient = context.getLlmClient();
        LOG.info("Agent 循环使用模型（流式）: " + llmClient.getModelName() + ", 模式: " + context.getMode());
        ToolRegistry registry = context.getToolRegistry();
        List<Tool> tools = context.getToolsForMode();
        int maxIterations = context.getMaxIterations();

        StringBuilder fullResponse = new StringBuilder();
        int[] totalUsage = {0, 0, 0}; // promptTokens, completionTokens, totalTokens
        int[] lastPromptTokens = {0}; // 最近一次 LLM 返回的实际 promptTokens，用于压缩决策

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            if (stopped) {
                LOG.info("Agent 循环被用户停止");
                LlmResponse.Usage usage = buildAccumulatedUsage(totalUsage);
                if (usage != null) listener.onUsage(usage);
                listener.onComplete(fullResponse.length() > 0 ? fullResponse.toString() : "[已停止生成]");
                return;
            }
            LOG.info("Agent 循环第 " + (iteration + 1) + " 次迭代（流式）");

            checkAndCompress(context, llmClient, lastPromptTokens[0], listener);

            final StringBuilder iterContent = new StringBuilder();
            final List<ChatMessage.ToolCall> iterToolCalls = new ArrayList<>();
            final String[] iterError = {null};
            final CountDownLatch latch = new CountDownLatch(1);

            llmClient.chatStream(
                    context.getConversation().getMessages(),
                    tools,
                    new LlmStreamListener() {
                        @Override
                        public void onContent(String delta) {
                            iterContent.append(delta);
                            listener.onContent(delta);
                        }

                        @Override
                        public void onToolCall(String toolCallId, String functionName, String arguments) {
                            ChatMessage.ToolCall tc = new ChatMessage.ToolCall();
                            tc.setId(toolCallId);
                            tc.setType("function");
                            ChatMessage.FunctionCall fc = new ChatMessage.FunctionCall();
                            fc.setName(functionName);
                            fc.setArguments(arguments);
                            tc.setFunction(fc);
                            iterToolCalls.add(tc);
                        }

                        @Override
                        public void onUsage(LlmResponse.Usage usage) {
                            totalUsage[0] += usage.getPromptTokens();
                            totalUsage[1] += usage.getCompletionTokens();
                            totalUsage[2] += usage.getTotalTokens();
                            // 记录 LLM 返回的实际 promptTokens，供下一轮压缩决策使用
                            lastPromptTokens[0] = usage.getPromptTokens();
                        }

                        @Override
                        public void onComplete() {
                            latch.countDown();
                        }

                        @Override
                        public void onError(String error, Throwable throwable) {
                            iterError[0] = error;
                            latch.countDown();
                        }
                    }
            );

            try {
                // 使用超时等待，防止 onFailure 未被调用导致线程永久阻塞
                boolean completed = latch.await(180, java.util.concurrent.TimeUnit.SECONDS);
                if (!completed) {
                    LOG.warn("Agent 循环第 " + (iteration + 1) + " 次迭代等待 LLM 响应超时（180s）");
                    llmClient.cancel();
                    listener.onError("LLM 响应超时（180秒），请检查网络连接或模型配置");
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                listener.onError("Agent 循环被中断");
                return;
            }

            // 用户点击停止：cancel 会触发 onFailure，但应视为正常结束
            if (stopped) {
                LOG.info("Agent 循环被用户停止");
                LlmResponse.Usage usage = buildAccumulatedUsage(totalUsage);
                if (usage != null) listener.onUsage(usage);
                listener.onComplete(fullResponse.length() > 0 ? fullResponse.toString() : "[已停止生成]");
                return;
            }

            if (iterError[0] != null) {
                LOG.warn("流式调用错误: " + iterError[0]);
                LOG.info("流式调用错误 - 迭代次数: " + (iteration + 1) + ", 模型: " + llmClient.getModelName());
                listener.onError(iterError[0]);
                return;
            }

            // 情况1: LLM 返回了工具调用
            if (!iterToolCalls.isEmpty()) {
                String content = iterContent.length() > 0 ? iterContent.toString() : null;

                context.getConversation().addAssistantToolCalls(
                        iterToolCalls.toArray(new ChatMessage.ToolCall[0]), content);

                if (content != null && !content.isEmpty()) {
                    fullResponse.append(content);
                }

                // 先在调用线程触发所有工具的 onToolCallStart（用于 UI 更新）
                for (ChatMessage.ToolCall toolCall : iterToolCalls) {
                    String toolName = toolCall.getFunction().getName();
                    String args = toolCall.getFunction().getArguments();
                    String toolCallId = toolCall.getId();
                    listener.onToolCallStart(toolCallId, toolName, args);
                    LOG.info("调用工具: " + toolName + " 参数: " + args);
                }

                // 异步并行执行所有工具调用
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                Map<String, String> toolResults = new ConcurrentHashMap<>();
                for (ChatMessage.ToolCall toolCall : iterToolCalls) {
                    String toolName = toolCall.getFunction().getName();
                    String args = toolCall.getFunction().getArguments();
                    String toolCallId = toolCall.getId();

                    if (!registry.isToolAllowed(toolName, context.getMode())) {
                        String err = "错误: 当前处于 Plan 模式（只读分析），工具 '" + toolName + "' 已被禁用。请先切换到 Build 模式（/build）再执行修改操作。";
                        listener.onToolCallEnd(toolCallId, toolName, err);
                        toolResults.put(toolCallId, err);
                        continue;
                    }

                    if ("run_command".equals(toolName)) {
                        futures.add(CompletableFuture.runAsync(() ->
                                handleRunCommand(context, toolCall, listener, toolResults), toolExecutor));
                    } else {
                        futures.add(CompletableFuture.runAsync(() -> {
                            if (stopped) return; // Fix 6: 停止检查
                            try {
                                Tool tool = registry.getTool(toolName);
                                String result;
                                if (tool != null) {
                                    try {
                                        result = tool.execute(args);
                                    } catch (Exception e) {
                                        LOG.error("工具 " + toolName + " 执行异常", e);
                                        result = "错误: 工具 '" + toolName + "' 执行异常: " + e.getMessage();
                                        listener.onContent("\n\n⚠️ 工具 " + toolName + " 执行异常: " + e.getMessage());
                                    }
                                } else {
                                    result = "错误: 未找到工具 '" + toolName + "'";
                                }
                                listener.onToolCallEnd(toolCallId, toolName, result);
                                LOG.info("工具 " + toolName + " 执行完成");
                                toolResults.put(toolCallId, result);
                            } catch (Exception e) {
                                // Fix 2: 确保任何异常都有结果记录
                                LOG.error("工具 " + toolName + " 回调异常", e);
                                String errResult = "错误: 工具 '" + toolName + "' 内部异常: " + e.getMessage();
                                listener.onToolCallEnd(toolCallId, toolName, errResult);
                                listener.onContent("\n\n⚠️ 工具 " + toolName + " 内部异常: " + e.getMessage());
                                toolResults.put(toolCallId, errResult);
                            }
                        }, toolExecutor));
                    }
                }

                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                } catch (Exception e) {
                    LOG.error("等待工具异步执行完成时发生异常", e);
                    listener.onContent("\n\n⚠️ 工具执行异常: " + e.getMessage());
                }

                // Fix 5: 按 LLM 输出的 tool_calls 顺序添加结果
                for (ChatMessage.ToolCall toolCall : iterToolCalls) {
                    String result = toolResults.get(toolCall.getId());
                    if (result != null) {
                        context.getConversation().addToolResult(result, toolCall.getId());
                    }
                }

                // 全部工具执行完成后，检查停止标志
                if (stopped) {
                    LOG.info("Agent 循环被用户停止（工具执行后）");
                    LlmResponse.Usage usage = buildAccumulatedUsage(totalUsage);
                    if (usage != null) listener.onUsage(usage);
                    listener.onComplete(fullResponse.length() > 0 ? fullResponse.toString() : "[已停止生成]");
                    return;
                }

                continue;
            }

            // 情况2: LLM 返回了纯文本回答（Agent 循环结束）
            String content = iterContent.length() > 0 ? iterContent.toString() : null;
            if (content != null) {
                fullResponse.append(content);
                context.getConversation().addAssistantMessage(content);
            } else {
                LOG.warn("Agent 循环第 " + (iteration + 1) + " 次迭代未收到任何内容");
                listener.onError("LLM 未返回任何内容，请检查模型配置或网络连接");
                return;
            }

            LlmResponse.Usage usage = buildAccumulatedUsage(totalUsage);
            if (usage != null) listener.onUsage(usage);
            listener.onComplete(fullResponse.toString());
            LOG.info("Agent 循环结束，共迭代 " + (iteration + 1) + " 次");
            return;
        }

        // 达到最大迭代次数
        String msg = "\n\n[Agent 已达到最大迭代次数 (" + maxIterations + ")，停止执行]";
        listener.onContent(msg);
        fullResponse.append(msg);
        LlmResponse.Usage usage = buildAccumulatedUsage(totalUsage);
        if (usage != null) listener.onUsage(usage);
        listener.onComplete(fullResponse.toString());
    }

    private static LlmResponse.Usage buildAccumulatedUsage(int[] totalUsage) {
        if (totalUsage[2] == 0) return null;
        LlmResponse.Usage usage = new LlmResponse.Usage();
        usage.setPromptTokens(totalUsage[0]);
        usage.setCompletionTokens(totalUsage[1]);
        usage.setTotalTokens(totalUsage[2]);
        return usage;
    }

    /**
     * 处理 run_command 命令执行
     * 使用 IDEA 内置终端执行命令（通过 RunCommandTool），用户可以在终端中实时看到命令和输出
     */
    private void handleRunCommand(AgentContext context, ChatMessage.ToolCall toolCall, AgentListener listener, Map<String, String> toolResults) {
        String toolCallId = toolCall.getId();
        String toolName = toolCall.getFunction().getName();
        String args = toolCall.getFunction().getArguments();

        try {
            // a. 解析命令参数
            JsonObject jsonArgs = JsonParser.parseString(args).getAsJsonObject();
            String command = jsonArgs.get("command").getAsString();

            // b. 检查是否危险命令
            boolean dangerous = isDangerousCommand(command);

            // c. 通知监听器
            listener.onCommandApproval(toolCallId, command, dangerous);

            // d. 对于危险命令：等待用户审批（轮询检查停止标志）
            if (dangerous) {
                ApprovalManager.CommandApproval ca = approvalManager.register(toolCallId);
                try {
                    // 使用轮询方式等待审批，以便能响应停止信号
                    boolean released = false;
                    long approvalTimeout = 300;
                    long elapsed = 0;
                    while (elapsed < approvalTimeout) {
                        if (stopped) {
                            approvalManager.reject(toolCallId);
                            String err = "命令执行已被用户停止";
                            listener.onToolCallEnd(toolCallId, toolName, err);
                            listener.onCommandResult(toolCallId, err);
                            toolResults.put(toolCallId, err);
                            return;
                        }
                        released = ca.latch.await(1, TimeUnit.SECONDS);
                        if (released) break;
                        elapsed++;
                    }
                    if (!released) {
                        approvalManager.reject(toolCallId);
                        String err = "命令审批超时，已取消执行";
                        listener.onToolCallEnd(toolCallId, toolName, err);
                        listener.onCommandResult(toolCallId, err);
                        toolResults.put(toolCallId, err);
                        return;
                    }
                    if (!ca.approved) {
                        String err = "命令执行已被取消";
                        listener.onToolCallEnd(toolCallId, toolName, err);
                        listener.onCommandResult(toolCallId, err);
                        toolResults.put(toolCallId, err);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    approvalManager.reject(toolCallId);
                    String err = "等待命令审批被中断";
                    listener.onToolCallEnd(toolCallId, toolName, err);
                    listener.onCommandResult(toolCallId, err);
                    toolResults.put(toolCallId, err);
                    return;
                }
            }

            // 执行前再次检查停止标志
            if (stopped) {
                String err = "命令执行已被用户停止";
                listener.onToolCallEnd(toolCallId, toolName, err);
                listener.onCommandResult(toolCallId, err);
                toolResults.put(toolCallId, err);
                return;
            }

            // e. 通过 RunCommandTool 在 IDEA 终端中执行命令（安全命令直接执行，危险命令审批后执行）
            listener.onCommandProgress(toolCallId, "开始在终端执行...");
            RunCommandTool runCommandTool = new RunCommandTool(project);
            activeRunCommandTools.put(toolCallId, runCommandTool);
            try {
                String result = runCommandTool.execute(args);
                listener.onCommandProgress(toolCallId, "终端执行完成");
                listener.onCommandResult(toolCallId, result);
                listener.onToolCallEnd(toolCallId, toolName, result);
                toolResults.put(toolCallId, result);
            } finally {
                activeRunCommandTools.remove(toolCallId);
            }

        } catch (Exception e) {
            LOG.error("处理 run_command 失败", e);
            String err = "处理命令失败: " + e.getMessage();
            listener.onToolCallEnd(toolCallId, toolName, err);
            listener.onCommandResult(toolCallId, err);
            toolResults.put(toolCallId, err);
        }
    }

    /**
     * 重置当前活跃会话
     */
    public void resetConversation() {
        getContext().resetConversation();
    }

    // ========== 上下文压缩 ==========

    /**
     * 检查是否需要压缩，如果需要则执行压缩
     * 优先使用 LLM 返回的实际 promptTokens，无历史数据时回退到 tiktoken 计数
     *
     * @param lastPromptTokens 上一轮 LLM 返回的实际 promptTokens，0 表示无历史数据
     */
    private void checkAndCompress(AgentContext context, LlmClient llmClient, int lastPromptTokens, AgentListener listener) {
        AiAgentSettings.ModelConfig config = AiAgentSettings.getInstance().getActiveModelConfig();
        int threshold = config.compressionThreshold;
        int maxTokens = AiAgentSettings.getInstance().getMaxTokens();

        List<ChatMessage> messages = context.getConversation().getMessages();
        int totalTokens;
        if (lastPromptTokens > 0) {
            // 使用 LLM 返回的实际 Token 数（已包含图片 token）
            totalTokens = lastPromptTokens;
            LOG.info("使用 LLM 实际 Token 数: " + totalTokens);
        } else {
            // 首次迭代无历史数据，回退到 tiktoken 计数（根据模型匹配 tokenizer）
            // 文本 tokenizer 无法统计图片，需额外加上图片 token 估算
            totalTokens = TokenCounter.countTokens(messages, llmClient.getModelName())
                    + estimateImageTokens(messages);
            LOG.info("使用 tiktoken 估算 Token 数（含图片）: " + totalTokens);
        }
        int thresholdTokens = (int) (maxTokens * threshold / 100.0);

        if (totalTokens > thresholdTokens) {
            LOG.info("Token 数 " + totalTokens + " 超过阈值 " + thresholdTokens + " (" + threshold + "% of " + maxTokens + ")，开始压缩");
            compressConversation(context, llmClient, totalTokens, listener);
        }
    }


    /**
     * 估算消息列表中所有图片消耗的 Token 数
     */
    private int estimateImageTokens(List<ChatMessage> messages) {
        int imageTokens = 0;
        for (ChatMessage msg : messages) {
            if (msg.getImageContents() != null) {
                for (ChatMessage.ImageContent img : msg.getImageContents()) {
                    // 尺寸未知，使用启发式估算
                    imageTokens += TokenCounter.estimateImageTokens(0, 0, img.getMimeType());
                }
            }
        }
        return imageTokens;
    }

    /**
     * 执行上下文压缩：摘要压缩，失败则回退到丢弃最旧50%消息
     */
    private void compressConversation(AgentContext context, LlmClient llmClient, int beforeTokens, AgentListener listener) {
        List<ChatMessage> messages = context.getConversation().getMessages();

        int systemIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if ("system".equals(messages.get(i).getRole())) {
                systemIdx = i;
                break;
            }
        }

        int recentStart = findRecentMessagesStart(messages);
        if (recentStart <= systemIdx + 1) {
            LOG.info("没有足够的旧消息可以压缩");
            return;
        }

        List<ChatMessage> olderMessages = messages.subList(systemIdx + 1, recentStart);
        List<ChatMessage> recentMessages = messages.subList(recentStart, messages.size());

        StringBuilder oldContent = new StringBuilder();
        for (ChatMessage msg : olderMessages) {
            String content = msg.getContent();
            if (content != null && !content.isEmpty()) {
                oldContent.append("[").append(msg.getRole()).append("]: ").append(content).append("\n\n");
            }
        }

        boolean wasStopped = stopped;
        stopped = false;

        try {
            List<ChatMessage> summaryRequest = new ArrayList<>();
            summaryRequest.add(ChatMessage.system("你是一个对话摘要助手。请将以下对话内容压缩为简洁的摘要。保留关键信息：讨论的主题、做出的决定、代码修改、工具调用结果等。摘要应足够详细以维持对话上下文，但要尽量简洁。"));
            summaryRequest.add(ChatMessage.user("请摘要以下对话内容：\n\n" + oldContent.toString()));

            LlmResponse summaryResponse = llmClient.chat(summaryRequest, null);

            if (summaryResponse != null && summaryResponse.isSuccess()
                    && summaryResponse.getContent() != null
                    && !summaryResponse.getContent().isEmpty()) {
                String summary = summaryResponse.getContent();

                List<ChatMessage> newMessages = new ArrayList<>();
                newMessages.add(ChatMessage.system("以下是对之前对话的摘要：\n" + summary));
                // 保留携带图片的历史消息（不因摘要压缩而丢弃图片内容）
                for (ChatMessage older : olderMessages) {
                    if (older.hasImages()) {
                        newMessages.add(older);
                    }
                }
                newMessages.addAll(recentMessages);

                context.getConversation().replaceMessages(newMessages);

                int afterTokens = TokenCounter.countTokens(context.getConversation().getMessages(), llmClient.getModelName());
                int savedPercent = beforeTokens > 0 ? (int) ((1.0 - (double) afterTokens / beforeTokens) * 100) : 0;

                LOG.info("上下文压缩完成: " + beforeTokens + " -> " + afterTokens + " tokens (节省 " + savedPercent + "%)");
                notifyCompression(beforeTokens, afterTokens);
            } else {
                String errMsg = "摘要压缩失败，回退到丢弃旧消息";
                LOG.warn(errMsg);
                listener.onContent("\n\n⚠️ " + errMsg);
                fallbackCompress(context, llmClient, olderMessages, recentMessages, beforeTokens, systemIdx);
            }
        } catch (Exception e) {
            String errMsg = "压缩异常，回退到丢弃旧消息: " + e.getMessage();
            LOG.warn(errMsg, e);
            listener.onContent("\n\n⚠️ " + errMsg);
            fallbackCompress(context, llmClient, olderMessages, recentMessages, beforeTokens, systemIdx);
        } finally {
            stopped = wasStopped;
        }
    }

    /**
     * 回退压缩：丢弃最旧50%的旧消息
     */
    private void fallbackCompress(AgentContext context, LlmClient llmClient, List<ChatMessage> olderMessages,
                                  List<ChatMessage> recentMessages, int beforeTokens, int systemIdx) {
        int keepCount = olderMessages.size() / 2;
        List<ChatMessage> keptOlder = olderMessages.subList(olderMessages.size() - keepCount, olderMessages.size());

        List<ChatMessage> newMessages = new ArrayList<>();
        newMessages.addAll(keptOlder);
        newMessages.addAll(recentMessages);

        context.getConversation().replaceMessages(newMessages);

        int afterTokens = TokenCounter.countTokens(context.getConversation().getMessages(), llmClient.getModelName());
        int savedPercent = beforeTokens > 0 ? (int) ((1.0 - (double) afterTokens / beforeTokens) * 100) : 0;

        LOG.info("回退压缩完成: " + beforeTokens + " -> " + afterTokens + " tokens (节省 " + savedPercent + "%)");
        notifyCompression(beforeTokens, afterTokens);
    }

    /**
     * 找到最近2轮对话的起始位置（从后往前找2个user消息）
     */
    private int findRecentMessagesStart(List<ChatMessage> messages) {
        int userCount = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                userCount++;
                if (userCount == 2) {
                    return i;
                }
            }
        }
        return 0;
    }

    /**
     * 通知前端压缩结果
     */
    private void notifyCompression(int beforeTokens, int afterTokens) {
        CompressionListener listener = this.compressionListener;
        if (listener != null) {
            listener.onCompressed(beforeTokens, afterTokens);
        }
    }

    /**
     * 手动触发压缩（供 ChatPanel 调用）
     */
    public void manualCompress() {
        AgentContext ctx = getContext();
        if (ctx == null) return;

        LlmClient llmClient = ctx.getLlmClient();
        List<ChatMessage> messages = ctx.getConversation().getMessages();
        int beforeTokens = TokenCounter.countTokens(messages, llmClient.getModelName());

        compressConversation(ctx, llmClient, beforeTokens, new AgentListener() {
            @Override public void onThinking() {}
            @Override public void onContent(String content) { LOG.info("压缩通知: " + content); }
            @Override public void onToolCallStart(String toolCallId, String toolName, String arguments) {}
            @Override public void onToolCallEnd(String toolCallId, String toolName, String result) {}
            @Override public void onComplete(String fullResponse) {}
            @Override public void onError(String error) { LOG.warn("手动压缩: " + error); }
        });
    }
}

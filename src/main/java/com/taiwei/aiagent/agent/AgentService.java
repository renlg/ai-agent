package com.taiwei.aiagent.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.llm.LlmClient;
import com.taiwei.aiagent.llm.LlmStreamListener;
import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.settings.AiAgentSettings;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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
    private volatile boolean stopped = false;
    private volatile LlmClient activeLlmClient = null;

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
        void onToolCallStart(String toolName, String arguments);

        /** 工具调用完成 */
        void onToolCallEnd(String toolName, String result);

        /** 命令需要审批（危险命令） */
        default void onCommandApproval(String toolCallId, String command, boolean isDangerous) {}

        /** 命令执行进度更新 */
        default void onCommandProgress(String toolCallId, String status) {}

        /** 命令执行结果 */
        default void onCommandResult(String toolCallId, String result) {}

        /** Agent 完成回答 */
        void onComplete(String fullResponse);

        /** 发生错误 */
        void onError(String error);
    }

    public AgentService(Project project) {
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
     */
    public void sendMessage(String sessionId, String userMessage, AgentListener listener) {
        AgentContext ctx;
        if (sessionId != null && !sessionId.isEmpty()) {
            sessionManager.getOrCreateSession(sessionId);
            ctx = sessionManager.getContext(sessionId);
        } else {
            ctx = sessionManager.getActiveContext();
        }

        // 添加用户消息到对话历史
        ctx.getConversation().addUserMessage(userMessage);

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
     */
    public void stopGeneration() {
        stopped = true;
        LlmClient client = activeLlmClient;
        if (client != null) {
            client.cancel();
        }
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
        LOG.info("Agent 循环使用模型（流式）: " + llmClient.getModelName());
        ToolRegistry registry = context.getToolRegistry();
        List<Tool> tools = registry.getAllTools();
        int maxIterations = context.getMaxIterations();

        StringBuilder fullResponse = new StringBuilder();

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            if (stopped) {
                LOG.info("Agent 循环被用户停止");
                listener.onComplete(fullResponse.length() > 0 ? fullResponse.toString() : "[已停止生成]");
                return;
            }
            LOG.info("Agent 循环第 " + (iteration + 1) + " 次迭代（流式）");

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
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                listener.onError("Agent 循环被中断");
                return;
            }

            // 用户点击停止：cancel 会触发 onFailure，但应视为正常结束
            if (stopped) {
                LOG.info("Agent 循环被用户停止");
                listener.onComplete(fullResponse.length() > 0 ? fullResponse.toString() : "[已停止生成]");
                return;
            }

            if (iterError[0] != null) {
                LOG.warn("流式调用错误: " + iterError[0]);
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

                for (ChatMessage.ToolCall toolCall : iterToolCalls) {
                    String toolName = toolCall.getFunction().getName();
                    String args = toolCall.getFunction().getArguments();

                    listener.onToolCallStart(toolName, args);
                    LOG.info("调用工具: " + toolName + " 参数: " + args);

                    if ("run_command".equals(toolName)) {
                        // ===== 命令执行特殊流程 =====
                        handleRunCommand(context, toolCall, listener);
                    } else {
                        // ===== 普通工具执行（同步） =====
                        Tool tool = registry.getTool(toolName);
                        String result;
                        if (tool != null) {
                            result = tool.execute(args);
                        } else {
                            result = "错误: 未找到工具 '" + toolName + "'";
                        }

                        listener.onToolCallEnd(toolName, result);
                        LOG.info("工具 " + toolName + " 执行完成");

                        context.getConversation().addToolResult(result, toolCall.getId());
                    }
                }

                continue;
            }

            // 情况2: LLM 返回了纯文本回答（Agent 循环结束）
            String content = iterContent.length() > 0 ? iterContent.toString() : null;
            if (content != null) {
                fullResponse.append(content);
            }

            context.getConversation().addAssistantMessage(content);

            listener.onComplete(fullResponse.toString());
            LOG.info("Agent 循环结束，共迭代 " + (iteration + 1) + " 次");
            return;
        }

        // 达到最大迭代次数
        String msg = "\n\n[Agent 已达到最大迭代次数 (" + maxIterations + ")，停止执行]";
        listener.onContent(msg);
        fullResponse.append(msg);
        listener.onComplete(fullResponse.toString());
    }

    /**
     * 处理 run_command 命令执行
     * 包含危险命令审批 + IntelliJ Terminal API 执行
     */
    private void handleRunCommand(AgentContext context, ChatMessage.ToolCall toolCall, AgentListener listener) {
        String toolCallId = toolCall.getId();
        String toolName = toolCall.getFunction().getName();
        String args = toolCall.getFunction().getArguments();

        try {
            // a. 解析命令参数
            JsonObject jsonArgs = JsonParser.parseString(args).getAsJsonObject();
            String command = jsonArgs.get("command").getAsString();
            int timeout = jsonArgs.has("timeout") ? jsonArgs.get("timeout").getAsInt() : 30;
            timeout = Math.min(timeout, 120);

            // b. 检查是否危险命令
            boolean dangerous = isDangerousCommand(command);

            // c. 通知监听器需要审批
            listener.onCommandApproval(toolCallId, command, dangerous);

            // d. 对于危险命令：等待用户审批（由 ChatPanel 执行并设置结果）
            if (dangerous) {
                ApprovalManager.CommandApproval ca = approvalManager.register(toolCallId);
                try {
                    boolean released = ca.latch.await(timeout + 60, TimeUnit.SECONDS);
                    if (!released) {
                        approvalManager.reject(toolCallId);
                        String err = "命令审批超时，已取消执行";
                        listener.onToolCallEnd(toolName, err);
                        listener.onCommandResult(toolCallId, err);
                        context.getConversation().addToolResult(err, toolCallId);
                        return;
                    }
                    if (!ca.approved) {
                        String err = "命令执行已被取消";
                        listener.onToolCallEnd(toolName, err);
                        listener.onCommandResult(toolCallId, err);
                        context.getConversation().addToolResult(err, toolCallId);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    approvalManager.reject(toolCallId);
                    String err = "等待命令审批被中断";
                    listener.onToolCallEnd(toolName, err);
                    listener.onCommandResult(toolCallId, err);
                    context.getConversation().addToolResult(err, toolCallId);
                    return;
                }
                // 危险命令的结果由 ChatPanel 设置到 approvalManager
                // ChatPanel 调用 approvalManager.setResult() 后这里就能获取到
                // 由于 setResult 已经 countDown，result 已在 ca 中
                String result = ca.result;
                if (result != null) {
                    listener.onCommandResult(toolCallId, result);
                    listener.onToolCallEnd(toolName, result);
                    context.getConversation().addToolResult(result, toolCallId);
                    return;
                } else {
                    String err = "命令未返回结果";
                    listener.onToolCallEnd(toolName, err);
                    listener.onCommandResult(toolCallId, err);
                    context.getConversation().addToolResult(err, toolCallId);
                    return;
                }
            } else {
                // 安全命令：直接在 AgentService 中用 OSProcessHandler 执行
                listener.onCommandProgress(toolCallId, "开始执行...");
                String result = executeCommandWithHandler(context, toolCallId, command, timeout, listener);
                listener.onCommandProgress(toolCallId, "执行完成");
                listener.onCommandResult(toolCallId, result);
                listener.onToolCallEnd(toolName, result);
                context.getConversation().addToolResult(result, toolCallId);
            }

        } catch (Exception e) {
            LOG.error("处理 run_command 失败", e);
            String err = "处理命令失败: " + e.getMessage();
            listener.onToolCallEnd(toolName, err);
            listener.onCommandResult(toolCallId, err);
            context.getConversation().addToolResult(err, toolCallId);
        }
    }

    /**
     * 使用 IntelliJ OSProcessHandler 执行命令
     */
    private String executeCommandWithHandler(AgentContext context, String toolCallId, String command, int timeout, AgentListener listener) {
        try {
            String basePath = context != null && context.getProject() != null
                    ? context.getProject().getBasePath() : null;
            if (basePath == null) {
                basePath = System.getProperty("user.dir");
            }

            GeneralCommandLine commandLine = new GeneralCommandLine("sh", "-c", command)
                    .withWorkDirectory(basePath);

            OSProcessHandler handler = new OSProcessHandler(commandLine);

            StringBuilder output = new StringBuilder();
            final CountDownLatch execLatch = new CountDownLatch(1);
            final int[] exitCode = {-1};

            handler.addProcessListener(new ProcessAdapter() {
                @Override
                public void onTextAvailable(ProcessEvent event, com.intellij.openapi.util.Key outputType) {
                    String text = event.getText();
                    output.append(text);
                    // 通知进度
                    listener.onCommandProgress(toolCallId, text.trim());
                }

                @Override
                public void processTerminated(ProcessEvent event) {
                    exitCode[0] = event.getExitCode();
                    execLatch.countDown();
                }
            });

            handler.startNotify();

            // 等待执行完成或超时
            boolean finished = execLatch.await(timeout, TimeUnit.SECONDS);
            if (!finished) {
                handler.destroyProcess();
                return "命令执行超时（" + timeout + "秒），已强制终止。\n\n部分输出:\n" + output;
            }

            String result = output.toString();
            if (result.length() > 30000) {
                result = result.substring(0, 30000) + "\n... [输出过长，已截断]";
            }

            return String.format("退出码: %d\n\n输出:\n%s", exitCode[0], result);

        } catch (Exception e) {
            return "执行命令失败: " + e.getMessage();
        }
    }

    /**
     * 重置当前活跃会话
     */
    public void resetConversation() {
        getContext().resetConversation();
    }
}

package com.taiwei.aiagent.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.llm.LlmClient;
import com.taiwei.aiagent.llm.LlmStreamListener;
import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.settings.AiAgentSettings;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolRegistry;
import com.taiwei.aiagent.tool.impl.RunCommandTool;

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
    private final Project project;
    private volatile boolean stopped = false;
    private volatile LlmClient activeLlmClient = null;
    private volatile RunCommandTool activeRunCommandTool = null;

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

        /** Agent 完成回答 */
        void onComplete(String fullResponse);

        /** 发生错误 */
        void onError(String error);
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
     * 同时停止 LLM 调用和正在执行的工具调用
     */
    public void stopGeneration() {
        stopped = true;
        LlmClient client = activeLlmClient;
        if (client != null) {
            client.cancel();
        }
        // 停止正在执行的命令工具
        RunCommandTool tool = activeRunCommandTool;
        if (tool != null) {
            tool.stop();
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
                    // 每个工具执行前检查是否已停止
                    if (stopped) {
                        LOG.info("Agent 循环被用户停止（工具执行前）");
                        listener.onComplete(fullResponse.length() > 0 ? fullResponse.toString() : "[已停止生成]");
                        return;
                    }

                    String toolName = toolCall.getFunction().getName();
                    String args = toolCall.getFunction().getArguments();
                    String toolCallId = toolCall.getId();

                    listener.onToolCallStart(toolCallId, toolName, args);
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

                        listener.onToolCallEnd(toolCallId, toolName, result);
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
     * 使用 IDEA 内置终端执行命令（通过 RunCommandTool），用户可以在终端中实时看到命令和输出
     */
    private void handleRunCommand(AgentContext context, ChatMessage.ToolCall toolCall, AgentListener listener) {
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
                            context.getConversation().addToolResult(err, toolCallId);
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
                        context.getConversation().addToolResult(err, toolCallId);
                        return;
                    }
                    if (!ca.approved) {
                        String err = "命令执行已被取消";
                        listener.onToolCallEnd(toolCallId, toolName, err);
                        listener.onCommandResult(toolCallId, err);
                        context.getConversation().addToolResult(err, toolCallId);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    approvalManager.reject(toolCallId);
                    String err = "等待命令审批被中断";
                    listener.onToolCallEnd(toolCallId, toolName, err);
                    listener.onCommandResult(toolCallId, err);
                    context.getConversation().addToolResult(err, toolCallId);
                    return;
                }
            }

            // 执行前再次检查停止标志
            if (stopped) {
                String err = "命令执行已被用户停止";
                listener.onToolCallEnd(toolCallId, toolName, err);
                listener.onCommandResult(toolCallId, err);
                context.getConversation().addToolResult(err, toolCallId);
                return;
            }

            // e. 通过 RunCommandTool 在 IDEA 终端中执行命令（安全命令直接执行，危险命令审批后执行）
            listener.onCommandProgress(toolCallId, "开始在终端执行...");
            RunCommandTool runCommandTool = new RunCommandTool(project);
            activeRunCommandTool = runCommandTool;
            try {
                String result = runCommandTool.execute(args);
                listener.onCommandProgress(toolCallId, "终端执行完成");
                listener.onCommandResult(toolCallId, result);
                listener.onToolCallEnd(toolCallId, toolName, result);
                context.getConversation().addToolResult(result, toolCallId);
            } finally {
                activeRunCommandTool = null;
            }

        } catch (Exception e) {
            LOG.error("处理 run_command 失败", e);
            String err = "处理命令失败: " + e.getMessage();
            listener.onToolCallEnd(toolCallId, toolName, err);
            listener.onCommandResult(toolCallId, err);
            context.getConversation().addToolResult(err, toolCallId);
        }
    }

    /**
     * 重置当前活跃会话
     */
    public void resetConversation() {
        getContext().resetConversation();
    }
}

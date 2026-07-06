package com.taiwei.aiagent.agent;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.llm.LlmClient;
import com.taiwei.aiagent.llm.LlmResponse;
import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolRegistry;

import java.util.List;
import java.util.function.Consumer;

/**
 * Agent 核心服务
 * 实现 Agent 循环：接收用户消息 → 调用 LLM → 执行工具 → 循环直到得到最终回答
 */
public class AgentService {

    private static final Logger LOG = Logger.getInstance(AgentService.class);

    private final AgentContext context;

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

        /** Agent 完成回答 */
        void onComplete(String fullResponse);

        /** 发生错误 */
        void onError(String error);
    }

    public AgentService(Project project) {
        this.context = new AgentContext(project);
    }

    public AgentContext getContext() {
        return context;
    }

    /**
     * 发送用户消息并执行 Agent 循环
     *
     * @param userMessage 用户消息
     * @param listener    事件监听器
     */
    public void sendMessage(String userMessage, AgentListener listener) {
        // 添加用户消息到对话历史
        context.getConversation().addUserMessage(userMessage);

        listener.onThinking();

        // 启动 Agent 循环
        executeAgentLoop(listener);
    }

    /**
     * Agent 核心循环
     * 反复调用 LLM，直到得到纯文本回答（无工具调用）或达到最大迭代次数
     */
    private void executeAgentLoop(AgentListener listener) {
        LlmClient llmClient = context.getLlmClient();
        ToolRegistry registry = context.getToolRegistry();
        List<Tool> tools = registry.getAllTools();
        int maxIterations = context.getMaxIterations();

        StringBuilder fullResponse = new StringBuilder();

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            LOG.info("Agent 循环第 " + (iteration + 1) + " 次迭代");

            // 调用 LLM
            LlmResponse response = llmClient.chat(
                    context.getConversation().getMessages(),
                    tools
            );

            if (!response.isSuccess()) {
                String error = "LLM 调用失败: " + response.getErrorMessage();
                LOG.warn(error);
                listener.onError(error);
                return;
            }

            // 情况1: LLM 返回了工具调用
            if (response.hasToolCalls()) {
                // 将带工具调用的助手消息加入历史
                context.getConversation().addAssistantToolCalls(
                        response.getToolCalls(), response.getContent());

                // 如果有文本内容也输出
                if (response.getContent() != null && !response.getContent().isEmpty()) {
                    listener.onContent(response.getContent());
                    fullResponse.append(response.getContent());
                }

                // 执行每个工具调用
                for (ChatMessage.ToolCall toolCall : response.getToolCalls()) {
                    String toolName = toolCall.getFunction().getName();
                    String args = toolCall.getFunction().getArguments();

                    listener.onToolCallStart(toolName, args);
                    LOG.info("调用工具: " + toolName + " 参数: " + args);

                    // 查找并执行工具
                    Tool tool = registry.getTool(toolName);
                    String result;
                    if (tool != null) {
                        result = tool.execute(args);
                    } else {
                        result = "错误: 未找到工具 '" + toolName + "'";
                    }

                    listener.onToolCallEnd(toolName, result);
                    LOG.info("工具 " + toolName + " 执行完成");

                    // 将工具结果加入对话历史
                    context.getConversation().addToolResult(result, toolCall.getId());
                }

                // 继续循环，让 LLM 根据工具结果继续推理
                continue;
            }

            // 情况2: LLM 返回了纯文本回答（Agent 循环结束）
            String content = response.getContent();
            if (content != null) {
                listener.onContent(content);
                fullResponse.append(content);
            }

            // 将助手回答加入历史
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
     * 重置会话
     */
    public void resetConversation() {
        context.resetConversation();
    }
}

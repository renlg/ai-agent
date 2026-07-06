package com.taiwei.aiagent.agent;

import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.agent.prompt.PromptManager;
import com.taiwei.aiagent.llm.LlmClient;
import com.taiwei.aiagent.llm.openai.OpenAiLlmClient;
import com.taiwei.aiagent.model.Conversation;
import com.taiwei.aiagent.settings.AiAgentSettings;
import com.taiwei.aiagent.tool.ToolRegistry;
import com.taiwei.aiagent.tool.impl.FileReadTool;
import com.taiwei.aiagent.tool.impl.FileWriteTool;
import com.taiwei.aiagent.tool.impl.RunCommandTool;
import com.taiwei.aiagent.tool.impl.SearchCodeTool;

/**
 * Agent 会话上下文
 * 持有一次 Agent 会话所需的所有组件：LLM 客户端、工具注册表、对话历史等
 */
public class AgentContext {

    private final Project project;
    private final Conversation conversation;
    private final ToolRegistry toolRegistry;
    private final LlmClient llmClient;
    private final PromptManager promptManager;

    /**
     * Agent 循环最大迭代次数（防止无限循环）
     */
    private static final int MAX_ITERATIONS = 10;

    public AgentContext(Project project) {
        this.project = project;

        // 初始化配置
        AiAgentSettings settings = AiAgentSettings.getInstance();

        // 创建 LLM 客户端
        this.llmClient = new OpenAiLlmClient(
                settings.getBaseUrl(),
                settings.getApiKey(),
                settings.getModel()
        );

        // 初始化 Prompt 管理器
        this.promptManager = new PromptManager(project);

        // 创建对话（带系统提示词）
        this.conversation = new Conversation(promptManager.buildSystemPrompt());

        // 注册工具
        this.toolRegistry = new ToolRegistry();
        registerDefaultTools();
    }

    /**
     * 注册默认工具
     */
    private void registerDefaultTools() {
        toolRegistry.register(new FileReadTool(project));
        toolRegistry.register(new FileWriteTool(project));
        toolRegistry.register(new SearchCodeTool(project));
        toolRegistry.register(new RunCommandTool(project));
    }

    /**
     * 重置对话（清空历史，保留系统提示词）
     */
    public void resetConversation() {
        conversation.clear();
    }

    // ========== Getters ==========

    public Project getProject() {
        return project;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public LlmClient getLlmClient() {
        return llmClient;
    }

    public PromptManager getPromptManager() {
        return promptManager;
    }

    public int getMaxIterations() {
        return MAX_ITERATIONS;
    }
}

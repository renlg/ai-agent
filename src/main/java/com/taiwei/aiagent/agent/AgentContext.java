package com.taiwei.aiagent.agent;

import com.intellij.openapi.diagnostic.Logger;
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
    private final PromptManager promptManager;

    private static final Logger LOG = Logger.getInstance(AgentContext.class);

    /**
     * Agent 循环最大迭代次数（防止无限循环）
     */
    private static final int MAX_ITERATIONS = 10;

    private LlmClient cachedClient;
    private String cachedBaseUrl;
    private String cachedApiKey;
    private String cachedModel;

    public AgentContext(Project project) {
        this.project = project;

        // 初始化 Prompt 管理器
        this.promptManager = new PromptManager(project);

        // 创建对话（带系统提示词）
        this.conversation = new Conversation(promptManager.buildSystemPrompt());

        // 注册工具
        this.toolRegistry = new ToolRegistry();
        registerDefaultTools();
    }

    /**
     * 从已恢复的 Conversation 创建上下文（用于从磁盘恢复会话）
     */
    public AgentContext(Project project, Conversation conversation) {
        this.project = project;
        this.promptManager = new PromptManager(project);
        this.conversation = conversation;
        this.toolRegistry = new ToolRegistry();
        registerDefaultTools();
    }

    /**
     * 根据当前设置创建 LLM 客户端，并关闭旧的缓存客户端
     */
    private LlmClient createLlmClient() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        String baseUrl = settings.getBaseUrl();
        String apiKey = settings.getApiKey();
        String model = settings.getModel();
        int activeIndex = settings.getActiveModelIndex();
        LOG.info("创建 LLM 客户端 - activeIndex=" + activeIndex
                + ", baseUrl=" + baseUrl + ", model=" + model);

        if (cachedClient != null) {
            cachedClient.close();
        }

        LlmClient client = new OpenAiLlmClient(baseUrl, apiKey, model);
        cachedClient = client;
        cachedBaseUrl = baseUrl;
        cachedApiKey = apiKey;
        cachedModel = model;
        return client;
    }

    /**
     * 切换模型（使缓存失效，下次 getLlmClient() 时重建）
     */
    public void switchModel(int modelIndex) {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        settings.setActiveModelIndex(modelIndex);
        cachedClient = null;
        cachedBaseUrl = null;
        cachedApiKey = null;
        cachedModel = null;
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

    /**
     * 动态获取 LLM 客户端
     * 配置未变时返回缓存实例，配置变更时重建并关闭旧客户端
     */
    public LlmClient getLlmClient() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        String baseUrl = settings.getBaseUrl();
        String apiKey = settings.getApiKey();
        String model = settings.getModel();

        if (cachedClient != null
                && java.util.Objects.equals(baseUrl, cachedBaseUrl)
                && java.util.Objects.equals(apiKey, cachedApiKey)
                && java.util.Objects.equals(model, cachedModel)) {
            return cachedClient;
        }

        return createLlmClient();
    }

    public PromptManager getPromptManager() {
        return promptManager;
    }

    public int getMaxIterations() {
        return MAX_ITERATIONS;
    }
}

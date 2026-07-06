package com.taiwei.aiagent.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * AI Agent 插件配置
 * 持久化存储 API Key、模型地址等设置
 */
@State(
        name = "AiAgentSettings",
        storages = @Storage("ai-agent-settings.xml")
)
public class AiAgentSettings implements PersistentStateComponent<AiAgentSettings.State> {

    private State state = new State();

    public static AiAgentSettings getInstance() {
        return ApplicationManager.getApplication().getService(AiAgentSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    // ========== 便捷访问方法 ==========

    public String getBaseUrl() {
        return state.baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        state.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return state.apiKey;
    }

    public void setApiKey(String apiKey) {
        state.apiKey = apiKey;
    }

    public String getModel() {
        return state.model;
    }

    public void setModel(String model) {
        state.model = model;
    }

    public int getMaxTokens() {
        return state.maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        state.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return state.temperature;
    }

    public void setTemperature(double temperature) {
        state.temperature = temperature;
    }

    /**
     * 配置状态（会被序列化到 XML）
     */
    public static class State {
        /**
         * API 基础地址
         * 默认 OpenAI，可改为 DeepSeek、通义千问等兼容接口
         */
        public String baseUrl = "https://api.openai.com/v1/";

        /**
         * API Key
         */
        public String apiKey = "";

        /**
         * 模型名称
         * 如 gpt-4o, deepseek-chat, qwen-turbo 等
         */
        public String model = "gpt-4o";

        /**
         * 最大输出 Token 数
         */
        public int maxTokens = 4096;

        /**
         * 温度参数（0-2）
         */
        public double temperature = 0.7;
    }
}

package com.taiwei.aiagent.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * AI Agent 插件配置
 * 支持多模型配置，可自由切换
 */
@State(
        name = "AiAgentSettings",
        storages = @Storage("ai-agent-settings.xml")
)
public class AiAgentSettings implements PersistentStateComponent<AiAgentSettings.State> {

    private State state = new State();
    private final List<Runnable> changeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static AiAgentSettings getInstance() {
        return ApplicationManager.getApplication().getService(AiAgentSettings.class);
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    public void fireSettingsChanged() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    // ========== 模型列表操作 ==========

    public List<ModelConfig> getModelConfigs() {
        return state.modelConfigs;
    }

    public void setModelConfigs(List<ModelConfig> configs) {
        state.modelConfigs = configs;
    }

    public void addModelConfig(ModelConfig config) {
        state.modelConfigs.add(config);
    }

    public void removeModelConfig(int index) {
        if (index >= 0 && index < state.modelConfigs.size()) {
            state.modelConfigs.remove(index);
            // 如果删除的是当前选中的，重置为第一个
            if (state.activeModelIndex >= state.modelConfigs.size()) {
                state.activeModelIndex = Math.max(0, state.modelConfigs.size() - 1);
            }
        }
    }

    public void updateModelConfig(int index, ModelConfig config) {
        if (index >= 0 && index < state.modelConfigs.size()) {
            state.modelConfigs.set(index, config);
        }
    }

    public ModelConfig getActiveModelConfig() {
        if (state.modelConfigs.isEmpty()) {
            return new ModelConfig(); // 返回默认
        }
        int idx = state.activeModelIndex;
        if (idx < 0 || idx >= state.modelConfigs.size()) {
            idx = 0;
            state.activeModelIndex = 0;
        }
        return state.modelConfigs.get(idx);
    }

    public int getActiveModelIndex() {
        return state.activeModelIndex;
    }

    public void setActiveModelIndex(int index) {
        if (index >= 0 && index < state.modelConfigs.size()) {
            state.activeModelIndex = index;
        } else {
            // 索引无效时打印警告，方便排查
            com.intellij.openapi.diagnostic.Logger.getInstance(AiAgentSettings.class)
                    .warn("setActiveModelIndex 失败: index=" + index + ", 模型数量=" + state.modelConfigs.size());
        }
    }

    // ========== 全局参数 ==========

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

    // ========== 便捷方法（兼容旧代码） ==========

    public String getBaseUrl() {
        return getActiveModelConfig().baseUrl;
    }

    public String getApiKey() {
        return getActiveModelConfig().apiKey;
    }

    public String getModel() {
        return getActiveModelConfig().modelName;
    }

    // ========== 危险命令配置 ==========

    public List<String> getDangerousCommands() {
        return state.dangerousCommands;
    }

    public void setDangerousCommands(List<String> commands) {
        state.dangerousCommands = commands;
    }

    // ========== 搜索引擎配置 ==========

    public String getSearchEngineType() {
        return state.searchEngineType;
    }

    public void setSearchEngineType(String searchEngineType) {
        state.searchEngineType = searchEngineType;
    }

    /**
     * 单个模型配置
     */
    public static class ModelConfig {
        public String name = "qwen3-max";          // 显示名称
        public String baseUrl = "";
        public String apiKey = "";
        public String modelName = "qwen3-max";
        public int compressionThreshold = 75;

        public ModelConfig() {}

        public ModelConfig(String name, String baseUrl, String apiKey, String modelName) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.modelName = modelName;
        }

        public ModelConfig(String name, String baseUrl, String apiKey, String modelName, int compressionThreshold) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.modelName = modelName;
            this.compressionThreshold = compressionThreshold;
        }

        public ModelConfig copy() {
            return new ModelConfig(name, baseUrl, apiKey, modelName, compressionThreshold);
        }
    }

    /**
     * 配置状态（会被序列化到 XML）
     */
    public static class State {
        /**
         * 模型配置列表
         */
        public List<ModelConfig> modelConfigs = new ArrayList<>();

        /**
         * 当前选中的模型索引
         */
        public int activeModelIndex = 0;

        /**
         * 最大输出 Token 数
         */
        public int maxTokens = 8192;

        /**
         * 温度参数（0-2）
         */
        public double temperature = 0.7;

        /**
         * 危险命令模式列表（匹配到任意模式的命令需要用户手动确认执行）
         */
        public List<String> dangerousCommands = new ArrayList<>(java.util.Arrays.asList(
                "rm -rf", "mkfs.", "dd if=", ":(){", "shutdown", "reboot", "halt",
                "poweroff", "init 0", "init 6", "fdisk", "format", "mv /", "chmod -R 777 /",
                "wget http", "curl -o", "> /dev/sda", "| sh", "eval ", "source /dev"
        ));

        /**
         * 搜索引擎类型：LOW_COST（DuckDuckGo 免费）或 ALIYUN_IQS（阿里云 IQS）
         */
        public String searchEngineType = "LOW_COST";

        public State() {
            // 默认添加一个模型
            modelConfigs.add(new ModelConfig());
        }
    }
}

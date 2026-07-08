package com.taiwei.aiagent.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 阿里云 IQS 网络搜索配置
 * 存储 AccessKey ID 和 AccessKey Secret
 */
@State(
        name = "IqsSettings",
        storages = @Storage("iqs-settings.xml")
)
public class IqsSettings implements PersistentStateComponent<IqsSettings.State> {

    private State state = new State();

    public static IqsSettings getInstance() {
        return ApplicationManager.getApplication().getService(IqsSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public String getAccessKeyId() {
        return state.accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        state.accessKeyId = accessKeyId;
    }

    public String getAccessKeySecret() {
        return state.accessKeySecret;
    }

    public void setAccessKeySecret(String accessKeySecret) {
        state.accessKeySecret = accessKeySecret;
    }

    /**
     * 检查是否已配置 AK/SK
     */
    public boolean isConfigured() {
        return state.accessKeyId != null && !state.accessKeyId.isEmpty()
                && state.accessKeySecret != null && !state.accessKeySecret.isEmpty();
    }

    /**
     * 配置状态
     */
    public static class State {
        public String accessKeyId = "";
        public String accessKeySecret = "";
    }
}

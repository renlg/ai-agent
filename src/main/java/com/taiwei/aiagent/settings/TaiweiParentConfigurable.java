package com.taiwei.aiagent.settings;

import com.intellij.openapi.options.Configurable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * 太微设置父节点
 * Settings → Tools → 太微
 * 本身无配置界面，仅作为"模型"和"终端"子节点的容器
 */
public class TaiweiParentConfigurable implements Configurable {

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "太微";
    }

    @Override
    public @Nullable JComponent createComponent() {
        return null;
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void apply() {
    }
}

package com.taiwei.aiagent.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.taiwei.aiagent.ui.ToolManagerDialog;
import com.taiwei.aiagent.util.I18nUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * 太微设置主页面
 * Settings → Tools → 太微
 * 提供功能开关：自动补全、Git 提交评审
 */
public class TaiweiParentConfigurable implements Configurable {

    private JPanel mainPanel;
    private JCheckBox completionCheckBox;
    private JCheckBox gitCommitReviewCheckBox;
    private JComboBox<String> toolManagerComboBox;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "太微";
    }

    @Override
    public @Nullable JComponent createComponent() {
        completionCheckBox = new JCheckBox(I18nUtil.getMessage("general.completionEnabled"));
        gitCommitReviewCheckBox = new JCheckBox(I18nUtil.getMessage("general.gitCommitReviewEnabled"));

        toolManagerComboBox = new JComboBox<>(new String[]{I18nUtil.getMessage("tool.manager.dropdown.option")});
        toolManagerComboBox.addActionListener(e -> openToolManager());
        JPanel toolManagerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        toolManagerPanel.add(toolManagerComboBox);

        mainPanel = FormBuilder.createFormBuilder()
                .addComponent(completionCheckBox)
                .addComponent(gitCommitReviewCheckBox)
                .addComponent(toolManagerPanel)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        mainPanel.setBorder(JBUI.Borders.empty(10));

        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        return completionCheckBox.isSelected() != settings.isCompletionEnabled()
                || gitCommitReviewCheckBox.isSelected() != settings.isGitCommitReviewEnabled();
    }

    @Override
    public void apply() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        settings.setCompletionEnabled(completionCheckBox.isSelected());
        settings.setGitCommitReviewEnabled(gitCommitReviewCheckBox.isSelected());
        settings.fireSettingsChanged();
    }

    @Override
    public void reset() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        completionCheckBox.setSelected(settings.isCompletionEnabled());
        gitCommitReviewCheckBox.setSelected(settings.isGitCommitReviewEnabled());
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        completionCheckBox = null;
        gitCommitReviewCheckBox = null;
        toolManagerComboBox = null;
    }

    private void openToolManager() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        if (projects.length == 0) {
            Messages.showInfoMessage(mainPanel, I18nUtil.getMessage("tool.manager.noProject"), I18nUtil.getMessage("tool.manager.button"));
            return;
        }
        new ToolManagerDialog(projects[0]).show();
    }
}

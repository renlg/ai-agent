package com.taiwei.aiagent.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * 网络搜索设置页面
 * Settings → Tools → 太微 → 网络搜索
 */
public class IqsConfigurable implements Configurable {

    private JPanel mainPanel;
    private JPasswordField accessKeyIdField;
    private JPasswordField accessKeySecretField;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "网络搜索";
    }

    @Override
    public @Nullable JComponent createComponent() {
        mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(JBUI.Borders.empty(12));

        // 标题
        JLabel titleLabel = new JLabel("阿里云 IQS 网络搜索配置");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(titleLabel);
        mainPanel.add(Box.createVerticalStrut(8));

        // 提示信息
        JTextArea hintArea = new JTextArea(
                "配置阿里云 AccessKey 后，Agent 可使用网络搜索工具获取实时信息。\n" +
                "请前往阿里云控制台（RAM 访问控制）创建 AccessKey，并开通 IQS 服务。\n" +
                "需确保 AK/SK 具有 AliyunIQSFullAccess 权限。"
        );
        hintArea.setEditable(false);
        hintArea.setFont(new Font("Dialog", Font.PLAIN, 12));
        hintArea.setBackground(UIManager.getColor("Panel.background"));
        hintArea.setForeground(UIManager.getColor("Label.disabledForeground"));
        hintArea.setBorder(JBUI.Borders.empty(0, 0, 16, 0));
        hintArea.setLineWrap(true);
        hintArea.setWrapStyleWord(true);
        hintArea.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(hintArea);

        // 输入面板
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);

        // AccessKey ID
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        JLabel akIdLabel = new JLabel("AccessKey ID:");
        akIdLabel.setPreferredSize(new Dimension(120, 28));
        formPanel.add(akIdLabel, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        accessKeyIdField = new JPasswordField(30);
        formPanel.add(accessKeyIdField, gbc);

        // AccessKey Secret
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        JLabel akSecretLabel = new JLabel("AccessKey Secret:");
        akSecretLabel.setPreferredSize(new Dimension(120, 28));
        formPanel.add(akSecretLabel, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        accessKeySecretField = new JPasswordField(30);
        formPanel.add(accessKeySecretField, gbc);

        mainPanel.add(formPanel);
        mainPanel.add(Box.createVerticalGlue());

        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        IqsSettings settings = IqsSettings.getInstance();
        return !getAccessKeyIdText().equals(settings.getAccessKeyId())
                || !getAccessKeySecretText().equals(settings.getAccessKeySecret());
    }

    @Override
    public void apply() throws ConfigurationException {
        String akId = getAccessKeyIdText().trim();
        String akSecret = getAccessKeySecretText().trim();

        if (akId.isEmpty() && akSecret.isEmpty()) {
            // 允许两者都为空（用户可能暂时不想配置）
            IqsSettings settings = IqsSettings.getInstance();
            settings.setAccessKeyId("");
            settings.setAccessKeySecret("");
            return;
        }

        if (akId.isEmpty()) {
            throw new ConfigurationException("AccessKey ID 不能为空");
        }
        if (akSecret.isEmpty()) {
            throw new ConfigurationException("AccessKey Secret 不能为空");
        }

        IqsSettings settings = IqsSettings.getInstance();
        settings.setAccessKeyId(akId);
        settings.setAccessKeySecret(akSecret);
    }

    @Override
    public void reset() {
        IqsSettings settings = IqsSettings.getInstance();
        accessKeyIdField.setText(settings.getAccessKeyId());
        accessKeySecretField.setText(settings.getAccessKeySecret());
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        accessKeyIdField = null;
        accessKeySecretField = null;
    }

    private String getAccessKeyIdText() {
        char[] password = accessKeyIdField.getPassword();
        return password != null ? new String(password) : "";
    }

    private String getAccessKeySecretText() {
        char[] password = accessKeySecretField.getPassword();
        return password != null ? new String(password) : "";
    }
}

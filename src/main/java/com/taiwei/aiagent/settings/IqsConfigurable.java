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
    private JComboBox<String> searchEngineCombo;
    private JPanel iqsConfigPanel;
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
        JLabel titleLabel = new JLabel("搜索引擎配置");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(titleLabel);
        mainPanel.add(Box.createVerticalStrut(8));

        // 搜索引擎选择
        JPanel enginePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        enginePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel engineLabel = new JLabel("搜索引擎: ");
        enginePanel.add(engineLabel);

        searchEngineCombo = new JComboBox<>(new String[]{"低成本默认（DuckDuckGo）", "阿里云 IQS"});
        searchEngineCombo.addActionListener(e -> onEngineChanged());
        enginePanel.add(searchEngineCombo);
        mainPanel.add(enginePanel);
        mainPanel.add(Box.createVerticalStrut(12));

        // IQS 配置面板（仅在选择阿里云 IQS 时显示）
        iqsConfigPanel = new JPanel();
        iqsConfigPanel.setLayout(new BoxLayout(iqsConfigPanel, BoxLayout.Y_AXIS));
        iqsConfigPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JTextArea hintArea = new JTextArea(
                "配置阿里云 AccessKey 后，Agent 可使用阿里云 IQS 网络搜索工具获取实时信息。\n" +
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
        iqsConfigPanel.add(hintArea);

        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);

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

        iqsConfigPanel.add(formPanel);
        mainPanel.add(iqsConfigPanel);
        mainPanel.add(Box.createVerticalGlue());

        reset();
        return mainPanel;
    }

    private void onEngineChanged() {
        boolean isIqs = searchEngineCombo.getSelectedIndex() == 1;
        iqsConfigPanel.setVisible(isIqs);
    }

    @Override
    public boolean isModified() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        String currentType = searchEngineCombo.getSelectedIndex() == 1 ? "ALIYUN_IQS" : "LOW_COST";
        if (!currentType.equals(settings.getSearchEngineType())) {
            return true;
        }
        if (searchEngineCombo.getSelectedIndex() == 1) {
            IqsSettings iqsSettings = IqsSettings.getInstance();
            return !getAccessKeyIdText().equals(iqsSettings.getAccessKeyId())
                    || !getAccessKeySecretText().equals(iqsSettings.getAccessKeySecret());
        }
        return false;
    }

    @Override
    public void apply() throws ConfigurationException {
        AiAgentSettings settings = AiAgentSettings.getInstance();

        if (searchEngineCombo.getSelectedIndex() == 1) {
            String akId = getAccessKeyIdText().trim();
            String akSecret = getAccessKeySecretText().trim();

            if (akId.isEmpty() && akSecret.isEmpty()) {
                IqsSettings iqsSettings = IqsSettings.getInstance();
                iqsSettings.setAccessKeyId("");
                iqsSettings.setAccessKeySecret("");
                settings.setSearchEngineType("ALIYUN_IQS");
                return;
            }

            if (akId.isEmpty()) {
                throw new ConfigurationException("AccessKey ID 不能为空");
            }
            if (akSecret.isEmpty()) {
                throw new ConfigurationException("AccessKey Secret 不能为空");
            }

            IqsSettings iqsSettings = IqsSettings.getInstance();
            iqsSettings.setAccessKeyId(akId);
            iqsSettings.setAccessKeySecret(akSecret);
            settings.setSearchEngineType("ALIYUN_IQS");
        } else {
            settings.setSearchEngineType("LOW_COST");
        }
    }

    @Override
    public void reset() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        boolean isIqs = "ALIYUN_IQS".equals(settings.getSearchEngineType());
        searchEngineCombo.setSelectedIndex(isIqs ? 1 : 0);
        iqsConfigPanel.setVisible(isIqs);

        IqsSettings iqsSettings = IqsSettings.getInstance();
        accessKeyIdField.setText(iqsSettings.getAccessKeyId());
        accessKeySecretField.setText(iqsSettings.getAccessKeySecret());
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        searchEngineCombo = null;
        iqsConfigPanel = null;
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

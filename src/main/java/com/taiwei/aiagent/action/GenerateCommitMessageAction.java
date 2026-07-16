package com.taiwei.aiagent.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.CommitMessageI;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.ui.Refreshable;
import com.taiwei.aiagent.llm.LlmClient;
import com.taiwei.aiagent.llm.LlmResponse;
import com.taiwei.aiagent.llm.openai.OpenAiLlmClient;
import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.settings.AiAgentSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * AI 生成提交信息动作（类似 Copilot/Cody 的 commit message generation）：
 * 出现在提交面板的提交信息工具栏中，读取 git 暂存区 diff（为空时回退到工作区 diff），
 * 交给 LLM 生成符合 Conventional Commits 风格的提交信息并填入输入框。
 */
public class GenerateCommitMessageAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(GenerateCommitMessageAction.class);

    /** 提交给 LLM 的 diff 最大字符数（过大时截断，避免挤爆上下文） */
    private static final int MAX_DIFF_CHARS = 16000;
    private static final int GIT_TIMEOUT_SECONDS = 10;

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(
                e.getProject() != null && getCommitPanel(e) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        CommitMessageI commitPanel = getCommitPanel(e);
        if (project == null || commitPanel == null) {
            return;
        }
        String basePath = project.getBasePath();
        if (basePath == null) {
            return;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "太微：正在生成提交信息...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                String diff = runGit(basePath, "diff", "--cached");
                if (diff == null || diff.isBlank()) {
                    // 暂存区为空时回退到工作区变更
                    diff = runGit(basePath, "diff");
                }
                String status = runGit(basePath, "status", "--porcelain");

                if ((diff == null || diff.isBlank()) && (status == null || status.isBlank())) {
                    notifyError(project, "没有检测到任何变更（git diff 为空），无法生成提交信息。");
                    return;
                }
                if (diff != null && diff.length() > MAX_DIFF_CHARS) {
                    diff = diff.substring(0, MAX_DIFF_CHARS) + "\n...（diff 过长，已截断）";
                }

                AiAgentSettings settings = AiAgentSettings.getInstance();
                LlmClient client = new OpenAiLlmClient(
                        settings.getBaseUrl(), settings.getApiKey(), settings.getModel());
                try {
                    List<ChatMessage> request = new ArrayList<>();
                    request.add(ChatMessage.system(
                            "你是一个 Git 提交信息生成助手。根据给定的变更 diff 生成一条提交信息："
                                    + "第一行为简洁的中文摘要（Conventional Commits 风格，如 feat:/fix:/refactor: 开头，不超过 72 字符）；"
                                    + "如变更较复杂，可空一行后用列表补充要点。只输出提交信息本身，不要任何解释或 Markdown 围栏。"));
                    request.add(ChatMessage.user(
                            "git status --porcelain:\n" + (status != null ? status : "")
                                    + "\n\ngit diff:\n" + (diff != null ? diff : "")));

                    LlmResponse response = client.chat(request, null);
                    if (response == null || !response.isSuccess()
                            || response.getContent() == null || response.getContent().isBlank()) {
                        String err = response != null ? response.getErrorMessage() : "LLM 未返回内容";
                        notifyError(project, "生成提交信息失败: " + err);
                        return;
                    }

                    String message = EditCodeAction.stripCodeFence(response.getContent());
                    ApplicationManager.getApplication().invokeLater(() ->
                            commitPanel.setCommitMessage(message));
                } catch (Exception ex) {
                    LOG.warn("生成提交信息失败", ex);
                    notifyError(project, "生成提交信息失败: " + ex.getMessage());
                } finally {
                    client.close();
                }
            }
        });
    }

    @Nullable
    private static CommitMessageI getCommitPanel(@NotNull AnActionEvent e) {
        Refreshable data = Refreshable.PANEL_KEY.getData(e.getDataContext());
        if (data instanceof CommitMessageI) {
            return (CommitMessageI) data;
        }
        return VcsDataKeys.COMMIT_MESSAGE_CONTROL.getData(e.getDataContext());
    }

    /**
     * 在项目根目录执行 git 命令，失败/超时/非 git 仓库时返回 null
     */
    @Nullable
    static String runGit(String basePath, String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            for (String arg : args) {
                command.add(arg);
            }
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(basePath));
            pb.redirectErrorStream(false);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            return output.toString();
        } catch (Exception e) {
            LOG.warn("执行 git 命令失败: " + String.join(" ", args) + " - " + e.getMessage());
            return null;
        }
    }

    private static void notifyError(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(() ->
                Messages.showErrorDialog(project, message, "太微 - 生成提交信息"));
    }
}

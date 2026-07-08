package com.taiwei.aiagent.diff;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import com.intellij.ui.EditorNotifications;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Function;

/**
 * Editor notification provider that displays a banner at the top of the editor
 * when the current file has un-reviewed AI changes.
 * Uses IntelliJ 2024.1+ EditorNotificationProvider API.
 */
public class DiffEditorNotificationProvider implements EditorNotificationProvider {

    @Override
    public @Nullable Function<? super @NotNull FileEditor, ? extends @Nullable JComponent> collectNotificationData(
            @NotNull Project project, @NotNull VirtualFile file) {

        return fileEditor -> {
            if (!(fileEditor instanceof TextEditor)) return null;

            DiffReviewService service = DiffReviewService.getInstance(project);
            String filePath = file.getPath();
            List<DiffEntry> fileDiffs = service.getDiffsForFile(filePath);
            long pendingCount = fileDiffs.stream().filter(e -> !e.isAccepted()).count();

            if (pendingCount == 0) {
                return null; // No notification needed
            }

            return createNotificationPanel(project, file, filePath, pendingCount, service);
        };
    }

    @NotNull
    private JComponent createNotificationPanel(@NotNull Project project, @NotNull VirtualFile file,
                                                String filePath, long pendingCount,
                                                DiffReviewService service) {
        EditorNotificationPanel panel = new EditorNotificationPanel();
        panel.setText("当前文件有 " + pendingCount + " 处未审查的 AI 更改");

        // "全部保留" button
        panel.createActionLabel("全部保留", () -> {
            service.acceptFile(filePath);
            refreshNotifications(project, file, service);
        });

        // "全部撤销" button
        panel.createActionLabel("全部撤销", () -> {
            service.revertFile(filePath);
            refreshNotifications(project, file, service);
        });

        // "逐条审查" button → open a diff viewer or toggle inline review mode
        panel.createActionLabel("逐条审查", () -> {
            // For now, this just scrolls to the first diff location in the editor
            scrollToFirstDiff(project, file, service, filePath);
        });

        // "隐藏" button
        panel.createActionLabel("隐藏", () -> {
            panel.setVisible(false);
        });

        return panel;
    }

    private void refreshNotifications(@NotNull Project project, @NotNull VirtualFile file,
                                      @NotNull DiffReviewService service) {
        // Listen for changes to re-evaluate the notification
        service.addListener(new DiffReviewListener() {
            @Override
            public void onDiffAdded(DiffEntry entry) {
                EditorNotifications.getInstance(project).updateNotifications(file);
            }

            @Override
            public void onDiffAccepted(DiffEntry entry) {
                EditorNotifications.getInstance(project).updateNotifications(file);
            }

            @Override
            public void onDiffReverted(DiffEntry entry) {
                EditorNotifications.getInstance(project).updateNotifications(file);
            }

            @Override
            public void onDiffCleared() {
                EditorNotifications.getInstance(project).updateNotifications(file);
            }
        });

        EditorNotifications.getInstance(project).updateNotifications(file);
    }

    private void scrollToFirstDiff(@NotNull Project project, @NotNull VirtualFile file,
                                   @NotNull DiffReviewService service, String filePath) {
        List<DiffEntry> fileDiffs = service.getDiffsForFile(filePath);
        if (fileDiffs.isEmpty()) return;

        DiffEntry first = fileDiffs.get(0);
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor != null && first.getNewContent() != null) {
            String[] lines = first.getNewContent().split("\n", -1);
            if (lines.length > 0) {
                // Scroll to approximately the location of the change
                editor.getCaretModel().moveToOffset(0);
            }
        }
    }
}

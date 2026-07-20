package com.taiwei.aiagent.diff;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.LocalFileSystem;
import org.jetbrains.annotations.NotNull;

import com.intellij.openapi.diagnostic.Logger;

import javax.swing.event.EventListenerList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Core service managing diff entries for AI-generated code changes.
 * Registered as a project-level service.
 */
public class DiffReviewService {

    private static final Logger LOG = Logger.getInstance(DiffReviewService.class);
    private static final int MAX_DIFFS = 50;

    private final Project project;
    private final List<DiffEntry> diffList = new ArrayList<>();
    private final EventListenerList listenerList = new EventListenerList();
    private final DiffHighlighter highlighter;
    private final Set<String> hiddenFiles = ConcurrentHashMap.newKeySet();
    private int currentIndex = 0;

    public DiffReviewService(@NotNull Project project) {
        this.project = project;
        this.highlighter = new DiffHighlighter(project);
    }

    public static DiffReviewService getInstance(@NotNull Project project) {
        return project.getService(DiffReviewService.class);
    }

    public DiffHighlighter getHighlighter() {
        return highlighter;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int index) {
        synchronized (diffList) {
            if (index >= 0 && index < diffList.size()) {
                this.currentIndex = index;
            }
        }
    }

    public DiffEntry getCurrentDiff() {
        synchronized (diffList) {
            if (diffList.isEmpty()) return null;
            if (currentIndex < 0 || currentIndex >= diffList.size()) return null;
            return diffList.get(currentIndex);
        }
    }

    public int getDiffCount() {
        synchronized (diffList) {
            return diffList.size();
        }
    }

    // ---- Hide / Show ----

    public boolean toggleHidden(String filePath) {
        if (hiddenFiles.contains(filePath)) {
            hiddenFiles.remove(filePath);
            return false;
        } else {
            hiddenFiles.add(filePath);
            return true;
        }
    }

    public boolean isDiffHidden(String filePath) {
        return hiddenFiles.contains(filePath);
    }

    // ---- Diff Management ----

    /**
     * Add a new diff entry. If the list exceeds MAX_DIFFS, the oldest entry is removed.
     */
    public void addDiff(DiffEntry entry) {
        DiffEntry removedEntry = null;
        synchronized (diffList) {
            if (diffList.size() >= MAX_DIFFS) {
                removedEntry = diffList.remove(0);
            }
            diffList.add(entry);
            currentIndex = diffList.size() - 1;
        }
        if (removedEntry != null) {
            highlighter.clearHighlightsForFile(removedEntry.getFilePath());
        }

        // Notify listeners
        fireDiffAdded(entry);

        // Open file and apply highlight via invokeLater
        ApplicationManager.getApplication().invokeLater(() -> {
            VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(entry.getFilePath());
            if (vf != null) {
                FileEditorManager.getInstance(project).openFile(vf, true);
            }
            if (!hiddenFiles.contains(entry.getFilePath())) {
                highlighter.highlight(entry);
            }
            //noinspection deprecation
            com.intellij.ui.EditorNotifications.getInstance(project).updateAllNotifications();
        });
    }

    /**
     * Mark a diff entry as accepted (reviewed and approved).
     */
    public void acceptDiff(@NotNull DiffEntry entry) {
        entry.setAccepted(true);
        fireOnIndexChanged();
    }

    /**
     * Accept all diffs for a given file path and remove them.
     */
    public void acceptByFile(String filePath) {
        synchronized (diffList) {
            diffList.removeIf(e -> {
                if (e.getFilePath().equals(filePath)) {
                    e.setAccepted(true);
                    return true;
                }
                return false;
            });
        }
        hiddenFiles.remove(filePath);
        adjustCurrentIndex();
        fireOnIndexChanged();
    }

    /**
     * Accept all diffs for a given file path (old name, delegates to acceptByFile).
     */
    public void acceptFile(String filePath) {
        acceptByFile(filePath);
    }

    /**
     * Accept all pending diffs.
     */
    public void acceptAll() {
        synchronized (diffList) {
            for (DiffEntry entry : diffList) {
                if (!entry.isAccepted()) {
                    entry.setAccepted(true);
                }
            }
        }
        fireOnIndexChanged();
    }

    /**
     * Revert a specific diff entry by restoring the old content in the changed region only,
     * so that user edits made outside that region (or after the AI change) are preserved.
     */
    public void revertDiff(@NotNull DiffEntry entry) {
        synchronized (diffList) {
            diffList.remove(entry);
        }

        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                Path filePath = Paths.get(entry.getFilePath());
                VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(filePath.toFile());
                if (vf != null) {
                    Document document = FileDocumentManager.getInstance().getDocument(vf);
                    if (document != null) {
                        boolean reverted = applyPartialRevert(document, entry.getFilePath(),
                                entry.getOldContent(), entry.getNewContent());
                        if (reverted) {
                            FileDocumentManager.getInstance().saveDocument(document);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("Revert diff 失败: " + entry.getFilePath(), e);
            }
        });

        // Clear highlights for this file
        Document document = getDocumentForFile(entry.getFilePath());
        if (document != null) {
            highlighter.clearHighlight(document);
        }

        fireOnIndexChanged();
        adjustCurrentIndex();
    }

    /**
     * Reverts only the region that differs between {@code oldContent} and {@code newContent}
     * (found via common prefix/suffix), leaving the rest of the document untouched. If the
     * changed region no longer matches {@code newContent} — meaning the user edited it after
     * the AI change — the user is asked to confirm before it is overwritten.
     *
     * @return true if the document was modified.
     */
    private boolean applyPartialRevert(Document document, String filePath, String oldContent, String newContent) {
        int minLen = Math.min(oldContent.length(), newContent.length());

        int prefixLen = 0;
        while (prefixLen < minLen && oldContent.charAt(prefixLen) == newContent.charAt(prefixLen)) {
            prefixLen++;
        }

        int suffixLen = 0;
        while (suffixLen < minLen - prefixLen
                && oldContent.charAt(oldContent.length() - 1 - suffixLen) == newContent.charAt(newContent.length() - 1 - suffixLen)) {
            suffixLen++;
        }

        int startOffset = prefixLen;
        int endOffset = newContent.length() - suffixLen;
        String oldFragment = oldContent.substring(prefixLen, oldContent.length() - suffixLen);

        String currentText = document.getText();
        String expectedRegion = newContent.substring(prefixLen, newContent.length() - suffixLen);
        int actualStart = Math.min(startOffset, currentText.length());
        int actualEnd = Math.max(actualStart, Math.min(currentText.length() - suffixLen, currentText.length()));
        String actualRegion = currentText.substring(actualStart, actualEnd);

        if (!expectedRegion.equals(actualRegion)) {
            // User has edited inside the AI-changed region — ask for confirmation
            boolean confirmed = Messages.showYesNoDialog(
                    project,
                    "The AI-changed region in " + filePath + " has been modified by you. Revert anyway?",
                    "Region Modified",
                    null
            ) == Messages.YES;
            if (!confirmed) {
                return false;
            }
        }

        int safeStart = Math.min(startOffset, document.getTextLength());
        int safeEnd = Math.max(safeStart, Math.min(endOffset, document.getTextLength()));
        document.replaceString(safeStart, safeEnd, oldFragment);
        return true;
    }

    /**
     * Revert all diffs for a given file path.
     */
    public void revertFile(String filePath) {
        revertByFile(filePath);
    }

    /**
     * Revert all diffs for a given file path and remove entries.
     */
    public void revertByFile(String filePath) {
        DiffEntry firstEntry;
        synchronized (diffList) {
            firstEntry = diffList.stream()
                    .filter(e -> e.getFilePath().equals(filePath))
                    .findFirst().orElse(null);
            diffList.removeIf(entry -> entry.getFilePath().equals(filePath));
        }

        if (firstEntry != null) {
            WriteCommandAction.runWriteCommandAction(project, "Revert AI Change", null, () -> {
                VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(filePath);
                if (vf != null) {
                    Document document = FileDocumentManager.getInstance().getDocument(vf);
                    if (document != null) {
                        applyPartialRevert(document, filePath, firstEntry.getOldContent(), firstEntry.getNewContent());
                    }
                }
            });
        }

        hiddenFiles.remove(filePath);
        adjustCurrentIndex();
        fireOnIndexChanged();
    }

    /**
     * Clear all diff entries and remove their highlights, without reverting file content.
     */
    public void clearHighlights() {
        List<DiffEntry> allDiffs;
        synchronized (diffList) {
            allDiffs = new ArrayList<>(diffList);
            diffList.clear();
        }

        for (DiffEntry entry : allDiffs) {
            // Clear highlights per file
            Document document = getDocumentForFile(entry.getFilePath());
            if (document != null) {
                highlighter.clearHighlight(document);
            }
        }

        adjustCurrentIndex();
        fireOnIndexChanged();
    }

    /**
     * Clear all diff entries without reverting.
     */
    public void clearAll() {
        synchronized (diffList) {
            diffList.clear();
        }
        fireOnAllCleared();
        adjustCurrentIndex();
    }

    // ---- Query Methods ----

    public List<DiffEntry> getDiffs() {
        synchronized (diffList) {
            return new ArrayList<>(diffList);
        }
    }

    public List<DiffEntry> getDiffsForFile(String filePath) {
        synchronized (diffList) {
            return diffList.stream()
                    .filter(e -> e.getFilePath().equals(filePath))
                    .collect(Collectors.toList());
        }
    }

    public List<DiffEntry> getPendingDiffs() {
        synchronized (diffList) {
            return diffList.stream()
                    .filter(e -> !e.isAccepted())
                    .collect(Collectors.toList());
        }
    }

    // ---- Listener Management ----

    public void addListener(DiffReviewListener listener) {
        listenerList.add(DiffReviewListener.class, listener);
    }

    public void removeListener(DiffReviewListener listener) {
        listenerList.remove(DiffReviewListener.class, listener);
    }

    // ---- Internal ----

    private void adjustCurrentIndex() {
        synchronized (diffList) {
            if (diffList.isEmpty()) {
                currentIndex = 0;
            } else if (currentIndex >= diffList.size()) {
                currentIndex = diffList.size() - 1;
            }
        }
    }

    private Document getDocumentForFile(String filePath) {
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(
                Paths.get(filePath).toFile());
        if (vf != null) {
            return FileDocumentManager.getInstance().getDocument(vf);
        }
        return null;
    }

    private void fireDiffAdded(DiffEntry entry) {
        for (DiffReviewListener listener : listenerList.getListeners(DiffReviewListener.class)) {
            listener.onDiffAdded(entry);
        }
    }

    private void fireOnIndexChanged() {
        for (DiffReviewListener listener : listenerList.getListeners(DiffReviewListener.class)) {
            listener.onIndexChanged(currentIndex);
        }
    }

    private void fireOnAllCleared() {
        for (DiffReviewListener listener : listenerList.getListeners(DiffReviewListener.class)) {
            listener.onAllCleared();
        }
    }
}

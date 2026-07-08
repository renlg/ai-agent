package com.taiwei.aiagent.diff;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.LocalFileSystem;
import org.jetbrains.annotations.NotNull;

import javax.swing.event.EventListenerList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Core service managing diff entries for AI-generated code changes.
 * Registered as a project-level service.
 */
public class DiffReviewService {

    private static final int MAX_DIFFS = 50;

    private final Project project;
    private final List<DiffEntry> diffList = new ArrayList<>();
    private final EventListenerList listenerList = new EventListenerList();

    public DiffReviewService(@NotNull Project project) {
        this.project = project;
    }

    public static DiffReviewService getInstance(@NotNull Project project) {
        return project.getService(DiffReviewService.class);
    }

    // ---- Diff Management ----

    /**
     * Add a new diff entry. If the list exceeds MAX_DIFFS, the oldest entry is removed.
     */
    public void addDiff(DiffEntry entry) {
        synchronized (diffList) {
            if (diffList.size() >= MAX_DIFFS) {
                diffList.remove(0);
            }
            diffList.add(entry);
        }

        // Notify listeners
        fireDiffAdded(entry);

        // Apply editor highlighting
        applyHighlightForEntry(entry);
    }

    /**
     * Mark a diff entry as accepted (reviewed and approved).
     */
    public void acceptDiff(@NotNull DiffEntry entry) {
        entry.setAccepted(true);
        fireDiffAccepted(entry);
    }

    /**
     * Accept all diffs for a given file path.
     */
    public void acceptFile(String filePath) {
        synchronized (diffList) {
            for (DiffEntry entry : diffList) {
                if (entry.getFilePath().equals(filePath) && !entry.isAccepted()) {
                    entry.setAccepted(true);
                    fireDiffAccepted(entry);
                }
            }
        }
    }

    /**
     * Accept all pending diffs.
     */
    public void acceptAll() {
        synchronized (diffList) {
            for (DiffEntry entry : diffList) {
                if (!entry.isAccepted()) {
                    entry.setAccepted(true);
                    fireDiffAccepted(entry);
                }
            }
        }
    }

    /**
     * Revert a specific diff entry by restoring the old content.
     */
    public void revertDiff(@NotNull DiffEntry entry) {
        synchronized (diffList) {
            diffList.remove(entry);
        }

        // Restore old content via WriteCommandAction
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                Path filePath = Paths.get(entry.getFilePath());
                VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(filePath.toFile());
                if (vf != null) {
                    Document document = FileDocumentManager.getInstance().getDocument(vf);
                    if (document != null) {
                        document.setText(entry.getOldContent());
                        FileDocumentManager.getInstance().saveDocument(document);
                    }
                }
            } catch (Exception e) {
                // Log error silently
            }
        });

        // Clear highlights for this file
        Document document = getDocumentForFile(entry.getFilePath());
        if (document != null) {
            DiffHighlighter.clearHighlight(project, document);
        }

        fireDiffReverted(entry);
    }

    /**
     * Revert all diffs for a given file path.
     */
    public void revertFile(String filePath) {
        List<DiffEntry> fileDiffs;
        synchronized (diffList) {
            fileDiffs = diffList.stream()
                    .filter(e -> e.getFilePath().equals(filePath))
                    .collect(Collectors.toList());
            diffList.removeAll(fileDiffs);
        }

        for (DiffEntry entry : fileDiffs) {
            fireDiffReverted(entry);
        }

        // Clear highlights
        Document document = getDocumentForFile(filePath);
        if (document != null) {
            DiffHighlighter.clearHighlight(project, document);
        }
    }

    /**
     * Revert all diffs across all files.
     */
    public void revertAll() {
        List<DiffEntry> allDiffs;
        synchronized (diffList) {
            allDiffs = new ArrayList<>(diffList);
            diffList.clear();
        }

        for (DiffEntry entry : allDiffs) {
            fireDiffReverted(entry);
            // Clear highlights per file
            Document document = getDocumentForFile(entry.getFilePath());
            if (document != null) {
                DiffHighlighter.clearHighlight(project, document);
            }
        }
    }

    /**
     * Clear all diff entries without reverting.
     */
    public void clearAll() {
        synchronized (diffList) {
            diffList.clear();
        }
        fireDiffCleared();
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

    // ---- Listener Management ----

    public void addListener(DiffReviewListener listener) {
        listenerList.add(DiffReviewListener.class, listener);
    }

    public void removeListener(DiffReviewListener listener) {
        listenerList.remove(DiffReviewListener.class, listener);
    }

    // ---- Internal ----

    private void applyHighlightForEntry(DiffEntry entry) {
        Document document = getDocumentForFile(entry.getFilePath());
        if (document != null) {
            DiffHighlighter.applyHighlight(project, document, entry.getOldContent(), entry.getNewContent());
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

    private void fireDiffAccepted(DiffEntry entry) {
        for (DiffReviewListener listener : listenerList.getListeners(DiffReviewListener.class)) {
            listener.onDiffAccepted(entry);
        }
    }

    private void fireDiffReverted(DiffEntry entry) {
        for (DiffReviewListener listener : listenerList.getListeners(DiffReviewListener.class)) {
            listener.onDiffReverted(entry);
        }
    }

    private void fireDiffCleared() {
        for (DiffReviewListener listener : listenerList.getListeners(DiffReviewListener.class)) {
            listener.onDiffCleared();
        }
    }
}

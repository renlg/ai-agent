package com.taiwei.aiagent.diff;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * LCS-based line-level diff algorithm with editor highlighting.
 * Computes diff between old and new content, then renders highlights and gutter icons.
 */
public class DiffHighlighter {

    private static final int LARGE_FILE_THRESHOLD = 5000;
    private static final Map<String, List<RangeHighlighter>> fileHighlighters = new HashMap<>();
    private static final Map<String, List<Inlay<?>>> fileInlays = new HashMap<>();
    private static final Map<String, List<RangeHighlighter>> fileGutterHighlighters = new HashMap<>();

    // Color constants for themes
    private static final Color ADDED_BG = JBColor.namedColor(
            "Editor.ModifiedBar.background.added",
            new JBColor(new Color(0x1a, 0x7f, 0x37, 0x30), new Color(0x1a, 0x7f, 0x37, 0x40))
    );
    private static final Color MODIFIED_BG = JBColor.namedColor(
            "Editor.ModifiedBar.background.modified",
            new JBColor(new Color(0x7a, 0x88, 0x00, 0x30), new Color(0x7a, 0x88, 0x00, 0x40))
    );
    private static final Color DELETED_LINE_BG = JBColor.namedColor(
            "Editor.ModifiedBar.background.deleted",
            new JBColor(new Color(0x8b, 0x00, 0x00, 0x20), new Color(0x8b, 0x00, 0x00, 0x30))
    );

    /**
     * Represents a line-level diff operation.
     */
    public static class DiffLine {
        public enum Type { EQUAL, ADDED, DELETED, MODIFIED }

        public final Type type;
        public final String oldLine;
        public final String newLine;
        public final int oldLineNum;
        public final int newLineNum;

        public DiffLine(Type type, String oldLine, String newLine, int oldLineNum, int newLineNum) {
            this.type = type;
            this.oldLine = oldLine;
            this.newLine = newLine;
            this.oldLineNum = oldLineNum;
            this.newLineNum = newLineNum;
        }
    }

    // ---- Public API ----

    /**
     * Apply diff highlights to the given document.
     */
    public static void applyHighlight(@NotNull Project project, @NotNull Document document,
                                      @NotNull String oldContent, @NotNull String newContent) {
        // Clear existing highlights for this document
        clearHighlight(project, document);

        String fileKey = getDocumentKey(document);
        List<DiffLine> diffLines = computeDiff(oldContent, newContent);

        MarkupModel markupModel = DocumentMarkupModel.forDocument(document, project, true);

        List<RangeHighlighter> highlighters = new ArrayList<>();
        List<Inlay<?>> inlays = new ArrayList<>();
        List<RangeHighlighter> gutterHighlighters = new ArrayList<>();

        int newLineIndex = 0;
        int oldLineIndex = 0;

        for (DiffLine diffLine : diffLines) {
            switch (diffLine.type) {
                case EQUAL:
                    newLineIndex++;
                    oldLineIndex++;
                    break;

                case ADDED:
                    highlightLine(markupModel, document, newLineIndex, DiffLine.Type.ADDED, highlighters, gutterHighlighters);
                    newLineIndex++;
                    break;

                case DELETED:
                    // Highlight a marker at the current position for deleted lines
                    highlightDeletedLine(markupModel, document, newLineIndex, oldLineIndex, diffLine.oldLine,
                            highlighters, gutterHighlighters);
                    // Create block inlay at current position showing deleted content
                    Inlay<?> inlay = createDeletedInlay(project, document, newLineIndex, diffLine.oldLine);
                    if (inlay != null) {
                        inlays.add(inlay);
                    }
                    oldLineIndex++;
                    break;

                case MODIFIED:
                    highlightLine(markupModel, document, newLineIndex, DiffLine.Type.MODIFIED, highlighters, gutterHighlighters);
                    newLineIndex++;
                    oldLineIndex++;
                    break;
            }
        }

        fileHighlighters.put(fileKey, highlighters);
        fileInlays.put(fileKey, inlays);
        fileGutterHighlighters.put(fileKey, gutterHighlighters);
    }

    /**
     * Clear all diff highlights for the given document.
     */
    public static void clearHighlight(@NotNull Project project, @NotNull Document document) {
        String fileKey = getDocumentKey(document);
        MarkupModel markupModel = DocumentMarkupModel.forDocument(document, project, true);

        // Remove range highlighters
        List<RangeHighlighter> highlighters = fileHighlighters.remove(fileKey);
        if (highlighters != null) {
            for (RangeHighlighter h : highlighters) {
                markupModel.removeHighlighter(h);
            }
        }

        // Remove gutter highlighters
        List<RangeHighlighter> gutterHighlighters = fileGutterHighlighters.remove(fileKey);
        if (gutterHighlighters != null) {
            for (RangeHighlighter h : gutterHighlighters) {
                markupModel.removeHighlighter(h);
            }
        }

        // Remove inlays
        List<Inlay<?>> inlays = fileInlays.remove(fileKey);
        if (inlays != null) {
            for (Inlay<?> inlay : inlays) {
                Disposer.dispose(inlay);
            }
        }
    }

    // ---- LCS Diff Algorithm ----

    /**
     * Compute line-level diff using LCS algorithm.
     * For files with >5000 lines, falls back to O(n) line-by-line comparison.
     */
    public static List<DiffLine> computeDiff(@NotNull String oldContent, @NotNull String newContent) {
        String[] oldLines = oldContent.isEmpty() ? new String[0] : oldContent.split("\n", -1);
        String[] newLines = newContent.isEmpty() ? new String[0] : newContent.split("\n", -1);

        // Large file optimization: use simple line-by-line comparison
        if (oldLines.length > LARGE_FILE_THRESHOLD || newLines.length > LARGE_FILE_THRESHOLD) {
            return computeSimpleDiff(oldLines, newLines);
        }

        return computeLcsDiff(oldLines, newLines);
    }

    /**
     * Full LCS-based diff for files under the threshold.
     */
    private static List<DiffLine> computeLcsDiff(String[] oldLines, String[] newLines) {
        int m = oldLines.length;
        int n = newLines.length;

        // Build LCS table
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (oldLines[i - 1].equals(newLines[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        // Backtrack to build diff
        List<DiffLine> reversed = new ArrayList<>();

        int i = m, j = n;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1].equals(newLines[j - 1])) {
                reversed.add(new DiffLine(DiffLine.Type.EQUAL, oldLines[i - 1], newLines[j - 1], i - 1, j - 1));
                i--;
                j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                reversed.add(new DiffLine(DiffLine.Type.ADDED, "", newLines[j - 1], i, j - 1));
                j--;
            } else if (i > 0) {
                reversed.add(new DiffLine(DiffLine.Type.DELETED, oldLines[i - 1], "", i - 1, j));
                i--;
            }
        }

        Collections.reverse(reversed);

        // Merge adjacent ADDED+DELETED into MODIFIED where possible
        return mergeToModified(reversed);
    }

    /**
     * Merge adjacent DELETED+ADDED pairs into MODIFIED entries.
     * LCS produces separate DELETED then ADDED for modified lines.
     */
    private static List<DiffLine> mergeToModified(List<DiffLine> diffLines) {
        List<DiffLine> result = new ArrayList<>();
        int idx = 0;
        while (idx < diffLines.size()) {
            DiffLine current = diffLines.get(idx);
            // Check if we have a DELETED followed by an ADDED → these form a MODIFIED
            if (current.type == DiffLine.Type.DELETED && idx + 1 < diffLines.size()
                    && diffLines.get(idx + 1).type == DiffLine.Type.ADDED) {
                DiffLine next = diffLines.get(idx + 1);
                result.add(new DiffLine(DiffLine.Type.MODIFIED, current.oldLine, next.newLine,
                        current.oldLineNum, next.newLineNum));
                idx += 2;
            } else {
                result.add(current);
                idx++;
            }
        }
        return result;
    }

    /**
     * Simple O(n) line-by-line diff for large files (>5000 lines).
     */
    private static List<DiffLine> computeSimpleDiff(String[] oldLines, String[] newLines) {
        List<DiffLine> result = new ArrayList<>();
        int maxLen = Math.max(oldLines.length, newLines.length);

        for (int i = 0; i < maxLen; i++) {
            boolean hasOld = i < oldLines.length;
            boolean hasNew = i < newLines.length;

            if (hasOld && hasNew) {
                if (oldLines[i].equals(newLines[i])) {
                    result.add(new DiffLine(DiffLine.Type.EQUAL, oldLines[i], newLines[i], i, i));
                } else {
                    result.add(new DiffLine(DiffLine.Type.MODIFIED, oldLines[i], newLines[i], i, i));
                }
            } else if (hasOld) {
                result.add(new DiffLine(DiffLine.Type.DELETED, oldLines[i], "", i, i));
            } else {
                result.add(new DiffLine(DiffLine.Type.ADDED, "", newLines[i], i, i));
            }
        }
        return result;
    }

    // ---- Highlighting Helpers ----

    private static void highlightLine(MarkupModel markupModel, Document document, int lineIndex,
                                      DiffLine.Type type, List<RangeHighlighter> highlighters,
                                      List<RangeHighlighter> gutterHighlighters) {
        if (lineIndex >= document.getLineCount()) return;

        int lineStart = document.getLineStartOffset(lineIndex);
        int lineEnd = document.getLineEndOffset(lineIndex);

        Color bgColor;
        switch (type) {
            case ADDED:
                bgColor = ADDED_BG;
                break;
            case MODIFIED:
                bgColor = MODIFIED_BG;
                break;
            default:
                return;
        }

        TextAttributes attrs = new TextAttributes();
        attrs.setBackgroundColor(bgColor);

        RangeHighlighter highlighter = markupModel.addRangeHighlighter(
                lineStart, lineEnd,
                HighlighterLayer.CARET_ROW - 1,
                attrs,
                HighlighterTargetArea.EXACT_RANGE
        );

        if (highlighter != null) {
            highlighters.add(highlighter);
        }

        // Add gutter icon for added/modified lines
        DiffEntry.DiffType diffType = type == DiffLine.Type.ADDED ? DiffEntry.DiffType.ADDED : DiffEntry.DiffType.MODIFIED;
        RangeHighlighter gutterHighlighter = markupModel.addRangeHighlighter(
                lineStart, lineEnd,
                HighlighterLayer.FIRST - 1,
                null,
                HighlighterTargetArea.EXACT_RANGE
        );
        if (gutterHighlighter != null) {
            gutterHighlighter.setGutterIconRenderer(new DiffGutterIconRenderer(diffType));
            gutterHighlighters.add(gutterHighlighter);
        }
    }

    private static void highlightDeletedLine(MarkupModel markupModel, Document document,
                                             int newLineIndex, int oldLineIndex, String deletedContent,
                                             List<RangeHighlighter> highlighters,
                                             List<RangeHighlighter> gutterHighlighters) {
        // Place a marker at the position where content was deleted
        if (newLineIndex < document.getLineCount()) {
            int lineStart = document.getLineStartOffset(newLineIndex);
            int lineEnd = document.getLineEndOffset(newLineIndex);

            TextAttributes attrs = new TextAttributes();
            attrs.setBackgroundColor(DELETED_LINE_BG);

            RangeHighlighter highlighter = markupModel.addRangeHighlighter(
                    lineStart, lineEnd,
                    HighlighterLayer.CARET_ROW - 1,
                    attrs,
                    HighlighterTargetArea.EXACT_RANGE
            );
            if (highlighter != null) {
                highlighters.add(highlighter);
            }
        }

        // Add gutter icon for deleted lines
        int targetLine = Math.min(newLineIndex, document.getLineCount() - 1);
        if (targetLine >= 0 && targetLine < document.getLineCount()) {
            int lineStart = Math.max(0, document.getLineStartOffset(targetLine));
            int lineEnd = document.getLineEndOffset(targetLine);

            RangeHighlighter gutterHighlighter = markupModel.addRangeHighlighter(
                    lineStart, lineEnd,
                    HighlighterLayer.FIRST - 1,
                    null,
                    HighlighterTargetArea.EXACT_RANGE
            );
            if (gutterHighlighter != null) {
                gutterHighlighter.setGutterIconRenderer(new DeletedLinesGutterRenderer(deletedContent));
                gutterHighlighters.add(gutterHighlighter);
            }
        }
    }

    private static Inlay<?> createDeletedInlay(@NotNull Project project, @NotNull Document document,
                                                int lineIndex, String deletedContent) {
        if (lineIndex >= document.getLineCount()) {
            // If content was at the end, attach to the last line
            lineIndex = Math.max(0, document.getLineCount() - 1);
        }

        Editor[] editors = EditorFactory.getInstance().getEditors(document, project);
        if (editors.length == 0) return null;

        Editor editor = editors[0];
        int offset = document.getLineStartOffset(lineIndex);

        DeletedBlockRenderer renderer = new DeletedBlockRenderer(deletedContent);
        Inlay<?> inlay = editor.getInlayModel().addBlockElement(offset, false, false, 0, renderer);

        return inlay;
    }

    private static String getDocumentKey(Document document) {
        return Integer.toHexString(System.identityHashCode(document));
    }
}

package com.taiwei.aiagent.diff;

import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Gutter icon renderer for deleted lines.
 * Shows a red '▾' icon. Tooltip displays the deleted content on hover.
 */
public class DeletedLinesGutterRenderer extends GutterIconRenderer {

    private static final Icon DELETED_ICON = IconLoader.getIcon("/icons/diff_deleted.svg", DeletedLinesGutterRenderer.class);

    private final String deletedContent;

    public DeletedLinesGutterRenderer(String deletedContent) {
        this.deletedContent = deletedContent;
    }

    @Override
    public @NotNull Icon getIcon() {
        return DELETED_ICON;
    }

    @Override
    public @Nullable String getTooltipText() {
        if (deletedContent == null || deletedContent.isEmpty()) {
            return "删除行";
        }
        // Show first 200 chars of deleted content in tooltip
        String preview = deletedContent.length() > 200
                ? deletedContent.substring(0, 200) + "..."
                : deletedContent;
        return "<html><body style='font-family: monospace; white-space: pre;'>已删除: " + escapeHtml(preview) + "</body></html>";
    }

    @Override
    public @NotNull Alignment getAlignment() {
        return Alignment.LEFT;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeletedLinesGutterRenderer)) return false;
        DeletedLinesGutterRenderer that = (DeletedLinesGutterRenderer) o;
        return deletedContent != null ? deletedContent.equals(that.deletedContent) : that.deletedContent == null;
    }

    @Override
    public int hashCode() {
        return deletedContent != null ? deletedContent.hashCode() : 0;
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
    }
}

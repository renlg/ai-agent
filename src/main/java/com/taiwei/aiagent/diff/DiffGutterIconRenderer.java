package com.taiwei.aiagent.diff;

import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Gutter icon renderer for added/modified lines.
 * Shows a green '+' icon for additions and an orange '~' icon for modifications.
 */
public class DiffGutterIconRenderer extends GutterIconRenderer {

    private static final Icon ADDED_ICON = IconLoader.getIcon("/icons/diff_added.svg", DiffGutterIconRenderer.class);
    private static final Icon MODIFIED_ICON = IconLoader.getIcon("/icons/diff_modified.svg", DiffGutterIconRenderer.class);

    private final DiffEntry.DiffType diffType;

    public DiffGutterIconRenderer(DiffEntry.DiffType diffType) {
        this.diffType = diffType;
    }

    @Override
    public @NotNull Icon getIcon() {
        return diffType == DiffEntry.DiffType.ADDED ? ADDED_ICON : MODIFIED_ICON;
    }

    @Override
    public @Nullable String getTooltipText() {
        return diffType == DiffEntry.DiffType.ADDED ? "新增行" : "修改行";
    }

    @Override
    public @NotNull Alignment getAlignment() {
        return Alignment.LEFT;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DiffGutterIconRenderer)) return false;
        DiffGutterIconRenderer that = (DiffGutterIconRenderer) o;
        return diffType == that.diffType;
    }

    @Override
    public int hashCode() {
        return diffType.hashCode();
    }
}

package com.taiwei.aiagent.diff;

/**
 * Represents a single diff entry tracking AI-generated code changes.
 */
public class DiffEntry {

    public enum DiffType {
        ADDED,    // Entirely new content (new file or new lines)
        MODIFIED, // Existing lines modified
        DELETED   // Lines removed
    }

    private final String filePath;
    private final String oldContent;
    private final String newContent;
    private final long timestamp;
    private boolean accepted;

    public DiffEntry(String filePath, String oldContent, String newContent) {
        this.filePath = filePath;
        this.oldContent = oldContent;
        this.newContent = newContent;
        this.timestamp = System.currentTimeMillis();
        this.accepted = false;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getOldContent() {
        return oldContent;
    }

    public String getNewContent() {
        return newContent;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public void setAccepted(boolean accepted) {
        this.accepted = accepted;
    }

    public DiffType getDiffType() {
        if (oldContent == null || oldContent.isEmpty()) {
            return DiffType.ADDED;
        }
        if (newContent == null || newContent.isEmpty()) {
            return DiffType.DELETED;
        }
        if (!oldContent.equals(newContent)) {
            return DiffType.MODIFIED;
        }
        return DiffType.MODIFIED;
    }

    @Override
    public String toString() {
        return "DiffEntry{" +
                "filePath='" + filePath + '\'' +
                ", type=" + getDiffType() +
                ", timestamp=" + timestamp +
                ", accepted=" + accepted +
                '}';
    }
}

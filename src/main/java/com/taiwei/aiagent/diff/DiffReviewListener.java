package com.taiwei.aiagent.diff;

import java.util.EventListener;

/**
 * Listener interface for diff review events.
 * Implementations are notified when diffs are added, accepted, reverted, or cleared.
 */
public interface DiffReviewListener extends EventListener {

    void onDiffAdded(DiffEntry entry);

    void onDiffAccepted(DiffEntry entry);

    void onDiffReverted(DiffEntry entry);

    void onDiffCleared();
}

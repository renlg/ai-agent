package com.taiwei.aiagent.memory;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Local, keyword-based long-term memory service (no vector DB / embeddings — see project notes).
 * Project-level service backed by a per-project SQLite file under {@code .taiwei/memories/memory.db}.
 */
public class MemoryManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(MemoryManager.class);

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{IsHan}\\p{L}\\p{N}]+");
    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;
    private static final long DECAY_PERIOD_MILLIS = 30 * DAY_MILLIS;

    /** Default cap on the on-disk size of the memory database. */
    public static final long MAX_STORAGE_BYTES = 50 * 1024 * 1024;

    private final MemoryStore store;
    private volatile long maxStorageBytes = MAX_STORAGE_BYTES;

    /** Constructor used by the IntelliJ project-service container. */
    public MemoryManager(@NotNull Project project) {
        this(new MemoryStore(defaultMemoryDbPath(project)));
    }

    public MemoryManager(MemoryStore store) {
        this.store = store;
    }

    public static MemoryManager getInstance(@NotNull Project project) {
        return project.getService(MemoryManager.class);
    }

    private static Path defaultMemoryDbPath(@NotNull Project project) {
        String basePath = project.getBasePath();
        return Paths.get(basePath, ".taiwei", "memories", "memory.db");
    }

    // ========== Core CRUD ==========

    public MemoryEntry remember(String content, MemoryCategory category, List<String> tags, int importance) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Memory content must not be blank");
        }
        if (getStorageUsageBytes() >= maxStorageBytes) {
            autoConsolidate();
            if (getStorageUsageBytes() >= maxStorageBytes) {
                LOG.warn("Rejecting new memory: storage usage " + getStorageUsageBytes()
                        + " bytes has reached the limit of " + maxStorageBytes
                        + " bytes even after auto-consolidation");
                throw new IllegalStateException(
                        "Memory storage limit reached (" + maxStorageBytes + " bytes); new memory was not saved");
            }
        }
        long now = System.currentTimeMillis();
        MemoryEntry entry = MemoryEntry.create(
                UUID.randomUUID().toString(),
                content.trim(),
                category == null ? MemoryCategory.FACT : category,
                tags == null ? List.of() : tags,
                clampImportance(importance),
                now);
        store.insert(entry);
        return entry;
    }

    public boolean forget(String id) {
        return store.deleteById(id);
    }

    /** Deletes every memory whose content or tags match the given keyword(s). Returns the number deleted. */
    public int forgetByQuery(String query) {
        Set<String> tokens = tokenize(query);
        if (tokens.isEmpty()) return 0;
        int deleted = 0;
        for (MemoryEntry entry : store.findAll()) {
            if (matchScore(tokens, entry) > 0) {
                store.deleteById(entry.getId());
                deleted++;
            }
        }
        return deleted;
    }

    public List<MemoryEntry> list(MemoryCategory category, int limit) {
        List<MemoryEntry> entries = (category == null)
                ? store.findAll()
                : store.findByCategory(category.name());
        entries.sort(Comparator.comparingLong(MemoryEntry::getLastAccessedAt).reversed());
        return limit > 0 && entries.size() > limit ? entries.subList(0, limit) : entries;
    }

    public List<String> getAllTags() {
        Set<String> tags = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (MemoryEntry entry : store.findAll()) {
            tags.addAll(entry.getTags());
        }
        return new ArrayList<>(tags);
    }

    public int getMemoryCount() {
        return store.count();
    }

    // ========== Search / Retrieval ==========

    /** Explicit keyword search across content + tags, sorted by relevance. Bumps access stats on returned entries. */
    public List<MemoryEntry> recall(String query, int limit) {
        Set<String> tokens = tokenize(query);
        if (tokens.isEmpty()) return List.of();
        return scoreAndRank(tokens, store.searchByKeyword(query), limit, true);
    }

    /** Used for automatic prompt injection: finds memories relevant to the current chat message. */
    public List<MemoryEntry> getRelevantMemories(String context, int limit) {
        return search(context, limit, true);
    }

    /** Formats memories relevant to {@code userMessage} as a "## Memory" style bullet list for prompt injection. */
    public String buildPromptContext(String userMessage, int limit) {
        List<MemoryEntry> memories = getRelevantMemories(userMessage, limit);
        if (memories.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry entry : memories) {
            sb.append("- ").append(entry.getContent());
            if (!entry.getTags().isEmpty()) {
                sb.append(" [").append(String.join(", ", entry.getTags())).append("]");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private List<MemoryEntry> search(String text, int limit, boolean touch) {
        Set<String> tokens = tokenize(text);
        if (tokens.isEmpty()) return List.of();
        List<MemoryEntry> ftsResults = store.searchByKeyword(text);
        if (!ftsResults.isEmpty()) {
            return scoreAndRank(tokens, ftsResults, limit, touch);
        }
        return scoreAndRank(tokens, store.findAll(50), limit, touch);
    }

    private List<MemoryEntry> scoreAndRank(Set<String> tokens, List<MemoryEntry> candidates, int limit, boolean touch) {
        long now = System.currentTimeMillis();
        List<ScoredEntry> scored = new ArrayList<>();
        for (MemoryEntry entry : candidates) {
            double relevance = matchScore(tokens, entry);
            if (relevance <= 0) continue;
            double finalScore = relevance * 3.0
                    + entry.getImportance()
                    + recencyScore(entry.getLastAccessedAt(), now) * 2.0
                    + Math.log(1 + entry.getAccessCount()) * 0.5;
            scored.add(new ScoredEntry(entry, finalScore));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        List<MemoryEntry> result = new ArrayList<>();
        for (int i = 0; i < scored.size() && i < limit; i++) {
            MemoryEntry entry = scored.get(i).entry;
            if (touch) {
                entry = entry.withAccessed(now);
                store.update(entry);
            }
            result.add(entry);
        }
        return result;
    }

    private double matchScore(Set<String> tokens, MemoryEntry entry) {
        String content = entry.getContent().toLowerCase(Locale.ROOT);
        double score = 0;
        for (String token : tokens) {
            if (content.contains(token)) {
                score += 1.0;
            }
            for (String tag : entry.getTags()) {
                if (tag.toLowerCase(Locale.ROOT).contains(token)) {
                    score += 2.0;
                }
            }
        }
        return score;
    }

    private double recencyScore(long lastAccessedAt, long now) {
        double daysSince = Math.max(0, (now - lastAccessedAt) / (double) DAY_MILLIS);
        return 1.0 / (1.0 + daysSince * 0.1);
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isBlank()) return tokens;
        for (String token : TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT))) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    // ========== Consolidation ==========

    /** Merges duplicate memories (same normalized content) and decays importance of stale, unaccessed entries. */
    public void autoConsolidate() {
        mergeDuplicates();
        decayStaleImportance();
    }

    private void mergeDuplicates() {
        Map<String, List<MemoryEntry>> byNormalizedContent = new HashMap<>();
        for (MemoryEntry entry : store.findAll()) {
            byNormalizedContent.computeIfAbsent(normalize(entry.getContent()), k -> new ArrayList<>()).add(entry);
        }

        long now = System.currentTimeMillis();
        for (List<MemoryEntry> group : byNormalizedContent.values()) {
            if (group.size() <= 1) continue;

            MemoryEntry keeper = group.get(0);
            Set<String> mergedTags = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            int totalAccessCount = 0;
            int maxImportance = keeper.getImportance();
            for (MemoryEntry candidate : group) {
                mergedTags.addAll(candidate.getTags());
                totalAccessCount += candidate.getAccessCount();
                maxImportance = Math.max(maxImportance, candidate.getImportance());
                if (candidate.getImportance() > keeper.getImportance()) {
                    keeper = candidate;
                }
            }

            MemoryEntry merged = keeper.withContentAndTags(
                    keeper.getContent(), new ArrayList<>(mergedTags), maxImportance, totalAccessCount, now);
            store.update(merged);

            for (MemoryEntry candidate : group) {
                if (!candidate.getId().equals(keeper.getId())) {
                    store.deleteById(candidate.getId());
                }
            }
        }
    }

    /**
     * Reduces importance by 1 for memories not accessed in 30+ days. Uses updatedAt (bumped on
     * each decay) rather than lastAccessedAt as the decay clock, so a memory only decays once
     * per elapsed 30-day window instead of every time this method runs.
     */
    private void decayStaleImportance() {
        long now = System.currentTimeMillis();
        for (MemoryEntry entry : store.findAll()) {
            boolean unaccessedForAWhile = now - entry.getLastAccessedAt() >= DECAY_PERIOD_MILLIS;
            boolean dueForDecayCheck = now - entry.getUpdatedAt() >= DECAY_PERIOD_MILLIS;
            if (unaccessedForAWhile && dueForDecayCheck && entry.getImportance() > 1) {
                store.update(entry.withImportance(entry.getImportance() - 1, now));
            }
        }
    }

    private String normalize(String content) {
        return content == null ? "" : content.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private int clampImportance(int importance) {
        if (importance < 1) return 1;
        if (importance > 10) return 10;
        return importance;
    }

    /** Returns the current size of the memory database file on disk, in bytes. */
    public long getStorageUsageBytes() {
        return store.getFileSizeBytes();
    }

    /** Returns the percentage of the configured storage limit currently in use (0.0 – 100.0). */
    public double getStorageUsagePercent() {
        return (double) getStorageUsageBytes() / maxStorageBytes * 100.0;
    }

    /** Dynamically changes the storage cap. Pass 0 or negative to disable the limit. */
    public void setMaxStorageBytes(long bytes) {
        this.maxStorageBytes = bytes;
    }

    private static class ScoredEntry {
        final MemoryEntry entry;
        final double score;

        ScoredEntry(MemoryEntry entry, double score) {
            this.entry = entry;
            this.score = score;
        }
    }

    @Override
    public void dispose() {
        store.close();
    }
}

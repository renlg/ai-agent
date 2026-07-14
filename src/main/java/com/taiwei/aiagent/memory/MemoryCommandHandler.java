package com.taiwei.aiagent.memory;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes "remember this" / "forget that" / "what do you know about X" style chat messages
 * so they can be intercepted and handled locally, before the message is ever sent to the LLM.
 */
public class MemoryCommandHandler {

    private static final Pattern REMEMBER_PATTERN = Pattern.compile(
            "^(?:记住|记一下|remember\\s+this\\s*:?)\\s*[:：]?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern FORGET_PATTERN = Pattern.compile(
            "^(?:忘了|忘掉|forget\\s+about)\\s*[:：]?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern RECALL_PATTERN = Pattern.compile(
            "^(?:我上次说的|what\\s+do\\s+you\\s+know\\s+about)\\s*[:：]?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final MemoryManager memoryManager;

    public MemoryCommandHandler(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    /** Returns the reply to show the user if {@code text} matched a memory command, else empty. */
    public Optional<String> tryHandle(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String trimmed = text.trim();

        Matcher rememberMatcher = REMEMBER_PATTERN.matcher(trimmed);
        if (rememberMatcher.matches()) {
            return Optional.of(handleRemember(rememberMatcher.group(1).trim()));
        }

        Matcher forgetMatcher = FORGET_PATTERN.matcher(trimmed);
        if (forgetMatcher.matches()) {
            return Optional.of(handleForget(forgetMatcher.group(1).trim()));
        }

        Matcher recallMatcher = RECALL_PATTERN.matcher(trimmed);
        if (recallMatcher.matches()) {
            return Optional.of(handleRecall(recallMatcher.group(1).trim()));
        }

        return Optional.empty();
    }

    private String handleRemember(String content) {
        if (content.isEmpty()) {
            return "好的，不过我没听清要记住的内容，请再说一次。";
        }
        memoryManager.remember(content, MemoryCategory.FACT, List.of(), 5);
        return "已记住：" + content;
    }

    private String handleForget(String query) {
        if (query.isEmpty()) {
            return "好的，不过我不确定要忘记什么，请说明关键词。";
        }
        int deleted = memoryManager.forgetByQuery(query);
        return deleted > 0
                ? "已忘记 " + deleted + " 条与\"" + query + "\"相关的记忆。"
                : "没有找到与\"" + query + "\"相关的记忆。";
    }

    private String handleRecall(String query) {
        if (query.isEmpty()) {
            return "你想让我回忆什么？";
        }
        List<MemoryEntry> matches = memoryManager.recall(query, 5);
        if (matches.isEmpty()) {
            return "我没有关于\"" + query + "\"的记忆。";
        }
        StringBuilder sb = new StringBuilder("关于\"").append(query).append("\"，我记得：\n");
        for (MemoryEntry entry : matches) {
            sb.append("- ").append(entry.getContent()).append("\n");
        }
        return sb.toString().trim();
    }
}

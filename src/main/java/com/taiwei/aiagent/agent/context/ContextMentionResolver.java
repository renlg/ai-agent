package com.taiwei.aiagent.agent.context;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析用户消息中的 @ 文件引用（类似 Continue / Copilot 的 @file 上下文提及）。
 * <p>
 * 语法：消息中出现 {@code @相对路径} 或 {@code @绝对路径} 时，若该路径在项目中真实存在，
 * 自动将文件内容（或目录清单）作为上下文附加到消息末尾。路径不存在的 @ 片段
 * （如邮箱、注解）会被安全忽略。
 */
public final class ContextMentionResolver {

    /** @后面的路径字符：字母数字、中文、路径分隔符、点、横线、下划线 */
    private static final Pattern MENTION_PATTERN =
            Pattern.compile("@([\\w\\p{IsHan}./\\\\-]+)");

    private static final int MAX_MENTIONS = 5;
    private static final int MAX_FILE_CHARS = 20000;
    private static final int MAX_DIR_ENTRIES = 100;

    private ContextMentionResolver() {
    }

    /**
     * 解析消息中的 @ 引用并返回附加了文件内容的消息。
     * 无有效引用时原样返回。
     *
     * @param basePath    项目根目录（可为 null，此时仅支持绝对路径引用）
     * @param userMessage 原始用户消息
     */
    public static String augment(String basePath, String userMessage) {
        if (userMessage == null || userMessage.indexOf('@') < 0) {
            return userMessage;
        }

        Set<String> mentions = new LinkedHashSet<>();
        Matcher matcher = MENTION_PATTERN.matcher(userMessage);
        while (matcher.find() && mentions.size() < MAX_MENTIONS) {
            mentions.add(matcher.group(1));
        }
        if (mentions.isEmpty()) {
            return userMessage;
        }

        StringBuilder attachments = new StringBuilder();
        for (String mention : mentions) {
            Path resolved = resolve(basePath, mention);
            if (resolved == null || !Files.exists(resolved)) {
                continue;
            }
            try {
                if (Files.isDirectory(resolved)) {
                    attachments.append("<directory path=\"").append(mention).append("\">\n")
                            .append(listDirectory(resolved))
                            .append("</directory>\n");
                } else if (Files.isRegularFile(resolved)) {
                    String content = Files.readString(resolved, StandardCharsets.UTF_8);
                    if (content.length() > MAX_FILE_CHARS) {
                        content = content.substring(0, MAX_FILE_CHARS)
                                + "\n... [文件过长，已截断，可用 read_file 指定行范围查看其余部分]";
                    }
                    attachments.append("<file path=\"").append(mention).append("\">\n")
                            .append(content);
                    if (!content.endsWith("\n")) {
                        attachments.append("\n");
                    }
                    attachments.append("</file>\n");
                }
            } catch (Exception e) {
                // 单个引用读取失败（二进制文件、权限等）不影响其余引用
            }
        }

        if (attachments.length() == 0) {
            return userMessage;
        }

        return userMessage
                + "\n\n---\n以下是消息中通过 @ 引用的文件/目录内容（系统自动附加）：\n"
                + attachments;
    }

    private static Path resolve(String basePath, String mention) {
        try {
            Path path = Paths.get(mention);
            if (path.isAbsolute()) {
                return path.normalize();
            }
            if (basePath == null) {
                return null;
            }
            return Paths.get(basePath, mention).normalize();
        } catch (Exception e) {
            // 非法路径字符（如 Windows 下的特殊字符）直接忽略
            return null;
        }
    }

    private static String listDirectory(Path dir) {
        StringBuilder sb = new StringBuilder();
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                entries.add(entry);
                if (entries.size() >= MAX_DIR_ENTRIES) {
                    break;
                }
            }
        } catch (Exception e) {
            return "(目录读取失败: " + e.getMessage() + ")\n";
        }
        entries.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        for (Path entry : entries) {
            sb.append(entry.getFileName().toString());
            if (Files.isDirectory(entry)) {
                sb.append("/");
            }
            sb.append("\n");
        }
        if (entries.size() >= MAX_DIR_ENTRIES) {
            sb.append("... [目录条目过多，已截断]\n");
        }
        return sb.toString();
    }
}

package com.taiwei.aiagent.skill;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Skill 管理器
 * 扫描项目 .taiwei/skills/&lt;name&gt;/SKILL.md 文件，解析 YAML frontmatter（name、description）
 * 并加载为可注入系统提示词的技能内容
 */
public class SkillManager {

    private static final Logger LOG = Logger.getInstance(SkillManager.class);
    private static final String SKILLS_DIR = "skills";
    private static final String SKILL_FILE = "SKILL.md";

    private final Project project;
    private final Map<String, SkillInfo> loadedSkills = new LinkedHashMap<>();

    public SkillManager(Project project) {
        this.project = project;
        loadSkills(project);
    }

    /**
     * 扫描并加载 .taiwei/skills/&lt;name&gt;/SKILL.md 文件
     */
    public void loadSkills(Project project) {
        loadedSkills.clear();

        String basePath = project.getBasePath();
        if (basePath == null) return;

        Path skillsDir = Paths.get(basePath, ".taiwei", SKILLS_DIR);

        try {
            if (!Files.exists(skillsDir)) {
                Files.createDirectories(skillsDir);
                return;
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir)) {
                for (Path dir : stream) {
                    if (!Files.isDirectory(dir)) continue;

                    Path skillFile = dir.resolve(SKILL_FILE);
                    if (!Files.isRegularFile(skillFile)) continue;

                    String raw = new String(Files.readAllBytes(skillFile), StandardCharsets.UTF_8);
                    SkillInfo info = parseSkill(dir.getFileName().toString(), raw);
                    if (info != null) {
                        loadedSkills.put(info.getName(), info);
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to load skills from " + skillsDir, e);
        }
    }

    /**
     * 解析 SKILL.md 的 YAML frontmatter（name、description）与正文内容
     */
    private SkillInfo parseSkill(String dirName, String raw) {
        String name = dirName;
        String description = "";
        String body = raw;

        String normalized = raw.replace("\r\n", "\n");
        if (normalized.startsWith("---\n")) {
            int end = normalized.indexOf("\n---", 4);
            if (end != -1) {
                String frontmatter = normalized.substring(4, end);
                int bodyStart = normalized.indexOf('\n', end + 1);
                body = bodyStart != -1 ? normalized.substring(bodyStart + 1) : "";

                for (String line : frontmatter.split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon <= 0) continue;
                    String key = line.substring(0, colon).trim();
                    String value = stripQuotes(line.substring(colon + 1).trim());
                    if ("name".equalsIgnoreCase(key) && !value.isEmpty()) {
                        name = value;
                    } else if ("description".equalsIgnoreCase(key)) {
                        description = value;
                    }
                }
            }
        }

        return new SkillInfo(name, description, body.trim());
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    public Map<String, SkillInfo> getLoadedSkills() {
        return loadedSkills;
    }

    public int getSkillCount() {
        return loadedSkills.size();
    }

    /**
     * 生成用于注入系统提示词的技能上下文字符串
     */
    public String getSkillsContext() {
        if (loadedSkills.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (SkillInfo skill : loadedSkills.values()) {
            sb.append("### ").append(skill.getName()).append("\n");
            if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
                sb.append(skill.getDescription()).append("\n\n");
            }
            sb.append(skill.getBody()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 单个 Skill 的解析结果
     */
    public static class SkillInfo {
        private final String name;
        private final String description;
        private final String body;

        public SkillInfo(String name, String description, String body) {
            this.name = name;
            this.description = description;
            this.body = body;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String getBody() {
            return body;
        }
    }
}

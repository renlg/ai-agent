package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.skill.Skill;
import com.taiwei.aiagent.skill.SkillManager;
import com.taiwei.aiagent.tool.Tool;

import java.util.Optional;

public class LoadSkillTool implements Tool {

    private final Project project;

    public LoadSkillTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "load_skill";
    }

    @Override
    public String getDescription() {
        return "加载指定 Skill 的完整内容。当你需要某个技能的详细指令时，使用此工具按需加载。可用的 Skill 列表见系统提示词的「可用的 Skill」部分。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "name": {
                      "type": "string",
                      "description": "要加载的 Skill 名称（与「可用的 Skill」列表中的名称一致）"
                    }
                  },
                  "required": ["name"]
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String name = args.get("name").getAsString();

            Optional<Skill> skill = SkillManager.getInstance(project).getSkill(name);
            if (skill.isEmpty()) {
                return "未找到名为「" + name + "」的 Skill。";
            }
            return skill.get().getContent();
        } catch (Exception e) {
            return "加载 Skill 失败：" + e.getMessage();
        }
    }

    @Override
    public boolean isMutating() {
        return false;
    }
}

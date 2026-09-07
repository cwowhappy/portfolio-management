package com.portfolio.invest.application.skill;

import io.agentscope.core.skill.AgentSkill;
import java.util.Map;

public record SkillView(String skillCode, String description, String category,
                        boolean defaultEnabled, String dependsOnProvider, boolean enabled) {
    public static SkillView from(AgentSkill skill, boolean enabled) {
        Map<String, Object> m = skill.getMetadata();
        return new SkillView(
                skill.getName(),
                skill.getDescription(),
                str(m, "category"),
                bool(m, "default_enabled"),
                str(m, "depends_on_provider"),
                enabled);
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static boolean bool(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Boolean b && b;
    }
}

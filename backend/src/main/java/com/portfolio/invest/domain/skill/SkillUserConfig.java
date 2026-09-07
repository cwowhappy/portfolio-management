package com.portfolio.invest.domain.skill;

import java.time.Instant;

/** 用户对某内置 skill 的启用选择：不可变，变更 update 返回新实例。无目录字段（目录在 classpath）。 */
public final class SkillUserConfig {
    private final Long id;
    private final Long userId;
    private final String skillCode;
    private final boolean enabled;
    private final Instant updatedAt;

    private SkillUserConfig(Long id, Long userId, String skillCode, boolean enabled, Instant updatedAt) {
        this.id = id; this.userId = userId; this.skillCode = skillCode; this.enabled = enabled; this.updatedAt = updatedAt;
    }

    public static SkillUserConfig create(Long userId, String skillCode, boolean enabled, Instant now) {
        if (userId == null) {
            throw new SkillException(SkillErrorCode.INVALID_INPUT, "用户不能为空");
        }
        if (skillCode == null || skillCode.isBlank()) {
            throw new SkillException(SkillErrorCode.INVALID_INPUT, "技能标识不能为空");
        }
        return new SkillUserConfig(null, userId, skillCode, enabled, now);
    }

    public static SkillUserConfig reconstitute(Long id, Long userId, String skillCode, boolean enabled, Instant updatedAt) {
        return new SkillUserConfig(id, userId, skillCode, enabled, updatedAt);
    }

    public SkillUserConfig update(boolean enabled, Instant now) {
        return new SkillUserConfig(id, userId, skillCode, enabled, now);
    }

    public Long id() { return id; }
    public Long userId() { return userId; }
    public String skillCode() { return skillCode; }
    public boolean enabled() { return enabled; }
    public Instant updatedAt() { return updatedAt; }
}

package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.skill.SkillUserConfig;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "skill_user_config")
public class SkillUserConfigJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "skill_code", nullable = false)
    private String skillCode;
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SkillUserConfigJpaEntity() {}

    public static SkillUserConfigJpaEntity fromDomain(SkillUserConfig c) {
        SkillUserConfigJpaEntity e = new SkillUserConfigJpaEntity();
        e.id = c.id(); e.userId = c.userId(); e.skillCode = c.skillCode();
        e.enabled = c.enabled(); e.updatedAt = c.updatedAt();
        return e;
    }

    public SkillUserConfig toDomain() {
        return SkillUserConfig.reconstitute(id, userId, skillCode, enabled, updatedAt);
    }
}

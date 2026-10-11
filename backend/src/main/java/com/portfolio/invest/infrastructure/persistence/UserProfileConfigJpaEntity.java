package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.memory.UserProfileConfig;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** user_profile_config JPA 实体（列映射照 SkillUserConfigJpaEntity 先例）。 */
@Entity
@Table(name = "user_profile_config")
public class UserProfileConfigJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "style_enabled", nullable = false)
    private boolean styleEnabled;
    @Column(name = "industry_enabled", nullable = false)
    private boolean industryEnabled;
    @Column(name = "watchlist_enabled", nullable = false)
    private boolean watchlistEnabled;
    @Column(name = "holdings_enabled", nullable = false)
    private boolean holdingsEnabled;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserProfileConfigJpaEntity() {}

    public static UserProfileConfigJpaEntity fromDomain(UserProfileConfig c) {
        UserProfileConfigJpaEntity e = new UserProfileConfigJpaEntity();
        e.id = c.id(); e.userId = c.userId();
        e.styleEnabled = c.styleEnabled(); e.industryEnabled = c.industryEnabled();
        e.watchlistEnabled = c.watchlistEnabled(); e.holdingsEnabled = c.holdingsEnabled();
        e.updatedAt = c.updatedAt();
        return e;
    }

    public UserProfileConfig toDomain() {
        return new UserProfileConfig(id, userId, styleEnabled, industryEnabled,
                watchlistEnabled, holdingsEnabled, updatedAt);
    }
}

package com.portfolio.invest.domain.memory;

import java.time.Instant;

/**
 * 用户画像注入授权（四开关，MS-31 设计规格 §3.1，user_profile_config 每用户一行）：
 * 不可变 record，变更 {@link #update} 返回新实例（沿 {@code SkillUserConfig} 先例）。
 * 无行 = 全关（缺省 opt-in，决议 #3）——由 {@link #defaults} 表达且不落库。
 */
public record UserProfileConfig(
        Long id,
        Long userId,
        boolean styleEnabled,
        boolean industryEnabled,
        boolean watchlistEnabled,
        boolean holdingsEnabled,
        Instant updatedAt) {

    /** 缺省实例：四开关全关（无行语义；读路径不 INSERT 占位行）。 */
    public static UserProfileConfig defaults(Long userId, Instant now) {
        return new UserProfileConfig(null, userId, false, false, false, false, now);
    }

    /** 全量替换四开关，保留 id/userId，updated_at 重打当前时刻。 */
    public UserProfileConfig update(boolean styleEnabled, boolean industryEnabled,
                                    boolean watchlistEnabled, boolean holdingsEnabled, Instant now) {
        return new UserProfileConfig(id, userId, styleEnabled, industryEnabled,
                watchlistEnabled, holdingsEnabled, now);
    }
}

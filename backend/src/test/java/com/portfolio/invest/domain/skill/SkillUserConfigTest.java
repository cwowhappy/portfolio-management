package com.portfolio.invest.domain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillUserConfigTest {
    private static final Instant NOW = Instant.parse("2026-09-07T08:00:00Z");

    @DisplayName("创建记录启用状态与 skill_code 落位")
    @Test
    void givenCreate_whenValid_thenFieldsSet() {
        var c = SkillUserConfig.create(1L, "tushare_data", true, NOW);
        assertThat(c.userId()).isEqualTo(1L);
        assertThat(c.skillCode()).isEqualTo("tushare_data");
        assertThat(c.enabled()).isTrue();
        assertThat(c.updatedAt()).isEqualTo(NOW);
    }

    @DisplayName("userId 为 null 抛 INVALID_INPUT")
    @Test
    void givenNullUserId_whenCreate_thenThrowInvalidInput() {
        assertThatThrownBy(() -> SkillUserConfig.create(null, "tushare_data", true, NOW))
                .isInstanceOfSatisfying(SkillException.class,
                        e -> assertThat(e.code()).isEqualTo(SkillErrorCode.INVALID_INPUT));
    }

    @DisplayName("skillCode 空白抛 INVALID_INPUT")
    @Test
    void givenBlankSkillCode_whenCreate_thenThrowInvalidInput() {
        assertThatThrownBy(() -> SkillUserConfig.create(1L, "  ", true, NOW))
                .isInstanceOfSatisfying(SkillException.class,
                        e -> assertThat(e.code()).isEqualTo(SkillErrorCode.INVALID_INPUT));
    }

    @DisplayName("更新返回新实例且原实例不变")
    @Test
    void givenUpdate_whenToggle_thenNewInstanceOriginalUnchanged() {
        var c = SkillUserConfig.create(1L, "tushare_data", true, NOW);
        var u = c.update(false, NOW.plusSeconds(10));
        assertThat(u.enabled()).isFalse();
        assertThat(u.updatedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(c.enabled()).isTrue();
        assertThat(c.updatedAt()).isEqualTo(NOW);
    }
}

package com.portfolio.invest.domain.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpUserConfigTest {
    private static final Instant NOW = Instant.parse("2026-09-06T08:00:00Z");

    @DisplayName("创建默认启用版本为1")
    @Test
    void givenCreate_whenDefault_thenEnabledAndVersion1() {
        var c = McpUserConfig.create(1L, 3L, List.of("get_stock_quote"), NOW);
        assertThat(c.enabled()).isTrue();
        assertThat(c.configVersion()).isEqualTo(1);
        assertThat(c.disabledTools()).containsExactly("get_stock_quote");
    }

    @DisplayName("禁用工具清单为null抛INVALID_INPUT")
    @Test
    void givenNullDisabledTools_whenCreate_thenThrowsInvalidInput() {
        assertThatThrownBy(() -> McpUserConfig.create(1L, 3L, null, NOW))
                .isInstanceOfSatisfying(McpException.class,
                        e -> assertThat(e.code()).isEqualTo(McpErrorCode.INVALID_INPUT));
    }

    @DisplayName("更新返回新实例且版本自增")
    @Test
    void givenUpdate_whenApplyChanges_thenNewInstanceAndVersionIncremented() {
        var c = McpUserConfig.create(1L, 3L, List.of(), NOW);
        var u = c.update(false, List.of("get_stock_kline"), NOW.plusSeconds(10));
        assertThat(u.enabled()).isFalse();
        assertThat(u.configVersion()).isEqualTo(2);
        assertThat(c.configVersion()).isEqualTo(1); // 原实例不变
    }
}

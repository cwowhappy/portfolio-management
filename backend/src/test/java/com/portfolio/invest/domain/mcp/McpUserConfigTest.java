package com.portfolio.invest.domain.mcp;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpUserConfigTest {
    private static final Instant NOW = Instant.parse("2026-09-06T08:00:00Z");

    @Test
    void 创建默认启用版本为1() {
        var c = McpUserConfig.create(1L, 3L, List.of("get_stock_quote"), NOW);
        assertThat(c.enabled()).isTrue();
        assertThat(c.configVersion()).isEqualTo(1);
        assertThat(c.disabledTools()).containsExactly("get_stock_quote");
    }

    @Test
    void 禁用工具清单为null抛INVALID_INPUT() {
        assertThatThrownBy(() -> McpUserConfig.create(1L, 3L, null, NOW))
                .isInstanceOfSatisfying(McpException.class,
                        e -> assertThat(e.code()).isEqualTo(McpErrorCode.INVALID_INPUT));
    }

    @Test
    void 更新返回新实例且版本自增() {
        var c = McpUserConfig.create(1L, 3L, List.of(), NOW);
        var u = c.update(false, List.of("get_stock_kline"), NOW.plusSeconds(10));
        assertThat(u.enabled()).isFalse();
        assertThat(u.configVersion()).isEqualTo(2);
        assertThat(c.configVersion()).isEqualTo(1); // 原实例不变
    }
}

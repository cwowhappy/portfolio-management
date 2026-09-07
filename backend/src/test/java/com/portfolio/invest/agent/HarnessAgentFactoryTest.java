package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HarnessAgentFactoryTest {

    @DisplayName("空启用集合 → 白名单空白名单，无 skill 放行")
    @Test
    void givenEmptyEnabled_whenSkillFilter_thenNoSkillAllowed() {
        var filter = HarnessAgentFactory.skillFilter(List.of());
        assertThat(filter.isAllowed("tushare_data")).isFalse();
        assertThat(filter.isAllowed("wind_finance")).isFalse();
    }

    @DisplayName("启用集合 → 仅启用的 skill 放行")
    @Test
    void givenEnabled_whenSkillFilter_thenOnlyEnabledAllowed() {
        var filter = HarnessAgentFactory.skillFilter(List.of("tushare_data"));
        assertThat(filter.isAllowed("tushare_data")).isTrue();
        assertThat(filter.isAllowed("wind_finance")).isFalse();
    }
}

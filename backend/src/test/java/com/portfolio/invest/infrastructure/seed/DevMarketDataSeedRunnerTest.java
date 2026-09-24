package com.portfolio.invest.infrastructure.seed;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DevMarketDataSeedRunnerTest {

    @DisplayName("未配置 E2E_DEV_SEED 时零副作用")
    @Test
    void givenFlagMissing_whenRun_thenNoQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new DevMarketDataSeedRunner(jdbc, null).run(null);
        verifyNoInteractions(jdbc);
    }

    @DisplayName("已配置但 stock_valuation_daily 已有数据时跳过脚本（幂等防污染）")
    @Test
    void givenFlagSetAndDataExists_whenRun_thenSkipScript() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(5);
        new DevMarketDataSeedRunner(jdbc, "1").run(null);
        verify(jdbc, never()).getDataSource();
    }
}

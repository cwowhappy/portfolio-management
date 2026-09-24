package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.analytics.RiskFreeRatePort;
import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.SortedMap;

import static org.assertj.core.api.Assertions.assertThat;

// 同 CloseAdapterIntegrationTest：@DataJpaTest 切片 + 真实 PG（继承 PostgresTestSupport 的 JVM 单例容器
// @DynamicPropertySource）；Boot 4 切片不含 Flyway 自动配置需 @ImportAutoConfiguration 显式引入；
// 纯 JdbcTemplate 读侧 Adapter 不在切片扫描范围内，用 @Import 显式装配。
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(RiskFreeRateAdapter.class)
class RiskFreeRateAdapterIntegrationTest extends PostgresTestSupport {

    @Autowired
    private RiskFreeRatePort riskFreeRate;

    // 沿 CloseAdapterIntegrationTest 的 @Autowired JdbcTemplate 方式声明（PostgresTestSupport 无该注入）
    @Autowired
    private JdbcTemplate jdbc;

    /** 共享容器还干净状态：只清本测试造数的三个交易日（不影响他测试种下的曲线行）。 */
    @AfterEach
    void cleanupSeededRows() {
        jdbc.update("DELETE FROM treasury_yield_curve WHERE trading_day IN (?, ?, ?)",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 6), LocalDate.of(2026, 1, 7));
    }

    @DisplayName("1Y 曲线区间查询：返回升序百分数序列且不受其他期限污染")
    @Test
    void givenCurveRows_whenOneYearSeries_thenAscendingPercentValues() {
        jdbc.update("INSERT INTO treasury_yield_curve (trading_day, term, yield) VALUES (?,'1Y',?) "
                        + "ON CONFLICT (trading_day, term) DO UPDATE SET yield = EXCLUDED.yield",
                LocalDate.of(2026, 1, 5), new BigDecimal("1.8500"));
        jdbc.update("INSERT INTO treasury_yield_curve (trading_day, term, yield) VALUES (?,'1Y',?) "
                        + "ON CONFLICT (trading_day, term) DO UPDATE SET yield = EXCLUDED.yield",
                LocalDate.of(2026, 1, 6), new BigDecimal("1.8600"));
        SortedMap<LocalDate, BigDecimal> series =
                riskFreeRate.oneYearSeries(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        assertThat(series).containsKeys(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 6));
        assertThat(series.get(LocalDate.of(2026, 1, 6))).isEqualByComparingTo("1.86");
        // 不含其他期限污染
        jdbc.update("INSERT INTO treasury_yield_curve (trading_day, term, yield) VALUES (?,'10Y',?) "
                        + "ON CONFLICT (trading_day, term) DO UPDATE SET yield = EXCLUDED.yield",
                LocalDate.of(2026, 1, 7), new BigDecimal("2.9"));
        assertThat(riskFreeRate.oneYearSeries(LocalDate.of(2026, 1, 7), LocalDate.of(2026, 1, 7))).isEmpty();
    }
}

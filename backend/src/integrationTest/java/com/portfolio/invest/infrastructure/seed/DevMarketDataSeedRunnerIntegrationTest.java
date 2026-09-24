package com.portfolio.invest.infrastructure.seed;

import com.portfolio.invest.domain.screening.ScreeningCriteria;
import com.portfolio.invest.domain.screening.ScreeningRepository;
import com.portfolio.invest.domain.screening.SortDirection;
import com.portfolio.invest.domain.screening.StockScreeningResult;
import com.portfolio.invest.infrastructure.persistence.ScreeningRepositoryImpl;
import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CI e2e 存量红回归钉：CI 的 invest 库只有 flyway schema、无行情数据时，
 * chat-tools 筛选用例因 screen_stocks 空结果不 emit 表格而 8/8 超时（issue #45 合并后排查）。
 * 本测试编码修复假设——DevMarketDataSeedRunner 应用 db/seed/valuation-dev-seed.sql 后，
 * 「ROE>15 且 PE<20」应命中五粮液(000858)与招商银行(600036)，即 e2e 断言的表格卡可渲染。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(ScreeningRepositoryImpl.class)
class DevMarketDataSeedRunnerIntegrationTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ScreeningRepository screeningRepository;

    /** 种子脚本经独立连接自提交，@Transactional 回滚不到；手工清表还共享容器以干净状态。 */
    @AfterEach
    void cleanupSeededTables() {
        for (String table : new String[]{
                "stock_valuation_daily", "stock_financial", "shenwan_industry_mapping",
                "valuation_snapshot", "treasury_yield_curve", "index_valuation_history",
                "industry_valuation"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    @DisplayName("空库应用种子后 ROE>15 且 PE<20 命中五粮液与招商银行")
    @Test
    void givenEmptyDb_whenRun_thenScreeningHitsSeededStocks() {
        new DevMarketDataSeedRunner(jdbcTemplate, "1").run(null);

        var criteria = new ScreeningCriteria(new BigDecimal("20"), null, null,
                new BigDecimal("15"), null, null, null, null, null, null, null, null,
                null, null, "pe_ttm", SortDirection.ASC, 200);
        List<StockScreeningResult> results = screeningRepository.findStocks(criteria);

        assertThat(results).extracting(StockScreeningResult::stockCode)
                .containsExactlyInAnyOrder("000858", "600036");
    }

    @DisplayName("重复执行幂等：不重复插入")
    @Test
    void givenSeededDb_whenRunAgain_thenNoDuplicate() {
        DevMarketDataSeedRunner runner = new DevMarketDataSeedRunner(jdbcTemplate, "1");
        runner.run(null);
        runner.run(null);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_valuation_daily", Integer.class);
        assertThat(rows).isEqualTo(5);
    }
}

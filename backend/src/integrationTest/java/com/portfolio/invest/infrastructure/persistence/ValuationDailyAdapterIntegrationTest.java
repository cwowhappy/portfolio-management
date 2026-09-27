package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.application.alert.StockMetric;
import com.portfolio.invest.application.alert.ValuationDailyPort;
import com.portfolio.invest.support.PostgresTestSupport;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(ValuationDailyAdapter.class)
class ValuationDailyAdapterIntegrationTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ValuationDailyPort port;

    @Test
    @DisplayName("给定两日两票数据，when查最新交易日与快照，then只返回指定日命中代码")
    void given两日数据_when查快照_then只返回指定日命中代码() {
        jdbc.update("INSERT INTO stock_valuation_daily (trading_day, stock_code, stock_name, pe_ttm, pb, close) "
                        + "VALUES (?, '600519', '贵州茅台', 45.5, 9.2, 1500), "
                        + "(?, '000858', '五粮液', 20.1, 4.5, 120), "
                        + "(?, '600519', '贵州茅台', 44.0, 9.0, 1490)",
                LocalDate.parse("2026-09-25"), LocalDate.parse("2026-09-25"), LocalDate.parse("2026-09-24"));
        assertThat(port.latestTradingDay()).contains(LocalDate.parse("2026-09-25"));
        Map<String, StockMetric> snapshots =
                port.snapshots(LocalDate.parse("2026-09-25"), List.of("600519", "601318"));
        assertThat(snapshots).containsOnlyKeys("600519");
        assertThat(snapshots.get("600519").peTtm()).isEqualByComparingTo("45.5");
        assertThat(snapshots.get("600519").close()).isEqualByComparingTo("1500");
    }

    @Test
    @DisplayName("给定空表，when查最新交易日，then空")
    void given空表_when查最新交易日_then空() {
        // 共享 JVM 单例容器会残留其他类的已提交行（DevMarketDataSeedRunner 经独立连接自提交），
        // 先清表使「空表」前置成立；清表本身在本用例回滚事务内，不外泄。
        jdbc.update("DELETE FROM stock_valuation_daily");
        assertThat(port.latestTradingDay()).isEmpty();
    }
}

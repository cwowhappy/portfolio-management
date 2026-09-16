package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.analytics.IndexClosePort;
import com.portfolio.invest.domain.analytics.StockClosePort;
import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

// 同 JournalEntryRepositoryImplTest：@DataJpaTest 切片 + 真实 PG（继承 PostgresTestSupport 的 JVM 单例容器
// @DynamicPropertySource）；Boot 4 切片不含 Flyway 自动配置需 @ImportAutoConfiguration 显式引入；
// 两个纯 JdbcTemplate 读侧 Adapter 不在切片扫描范围内，用 @Import 显式装配。
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({StockCloseAdapter.class, IndexCloseAdapter.class})
class CloseAdapterIntegrationTest extends PostgresTestSupport {

    @Autowired
    private StockClosePort stockClose;

    @Autowired
    private IndexClosePort indexClose;

    // 沿 FlywayMigrationIntegrationTest 的 @Autowired JdbcTemplate 方式声明（PostgresTestSupport 无该注入）
    @Autowired
    private JdbcTemplate jdbc;

    @DisplayName("个股收盘价区间查询：NULL close 行被跳过")
    @Test
    void givenSeededCloses_whenQuery_thenAscMapWithoutNulls() {
        jdbc.update("INSERT INTO stock_valuation_daily (trading_day, stock_code, stock_name, close) "
                + "VALUES (?, '600519', '贵州茅台', 1500), (?, '600519', '贵州茅台', 1510), "
                + "(?, '600519', '贵州茅台', NULL)",
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 16));
        var m = stockClose.closes("600519", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertThat(m).containsOnlyKeys(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 15));
        assertThat(m).doesNotContainValue(null);
    }

    @DisplayName("指数收盘价区间查询")
    @Test
    void givenSeededIndex_whenQuery_thenAscMap() {
        jdbc.update("INSERT INTO index_close_history (trading_day, index_code, index_name, close) "
                + "VALUES (?, '000300', '沪深300', 3900.12)", LocalDate.of(2026, 9, 15));
        var m = indexClose.closes("000300", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertThat(m.get(LocalDate.of(2026, 9, 15))).isEqualByComparingTo("3900.12");
    }
}

package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.valuation.ValuationRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

// @DataJpaTest 切片 + 真实 PG：@ServiceConnection 复用 testFixtures 的 JVM 单例容器，整个测试进程只起一个 Postgres。
// Boot 4 的 @DataJpaTest 不含 Flyway 自动配置（schema 由 Flyway 管），需 @ImportAutoConfiguration 显式引入；
// RepositoryImpl 适配器不在切片扫描范围内，用 @Import 显式装配。
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(ValuationRepositoryImpl.class)
class ValuationRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private ValuationRepository valuationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void seed() {
        jdbcTemplate.update("INSERT INTO valuation_snapshot(trading_day, pe_median, pb_median, net_breaker_count, net_breaker_ratio) VALUES (?, ?, ?, ?, ?)",
                java.sql.Date.valueOf("2026-08-27"), new java.math.BigDecimal("19.14"), new java.math.BigDecimal("1.68"), 220, new java.math.BigDecimal("0.0410"));
        jdbcTemplate.update("INSERT INTO treasury_yield_curve(trading_day, term, yield) VALUES (?, ?, ?)",
                java.sql.Date.valueOf("2026-08-27"), "10Y", new java.math.BigDecimal("2.21"));
        jdbcTemplate.update("INSERT INTO industry_valuation(trading_day, industry_code, industry_name, pe, pb) VALUES (?, ?, ?, ?, ?)",
                java.sql.Date.valueOf("2026-08-27"), "801780", "银行", new java.math.BigDecimal("5.9"), new java.math.BigDecimal("0.65"));
        jdbcTemplate.update("INSERT INTO index_valuation_history(trading_day, index_code, index_name, pe, pb, dividend_yield) VALUES (?, ?, ?, ?, ?, ?)",
                java.sql.Date.valueOf("2026-08-27"), "000300", "沪深300", new java.math.BigDecimal("12.8"), new java.math.BigDecimal("1.42"), new java.math.BigDecimal("2.35"));
    }

    @Test
    @Transactional
    void 查询最新快照() {
        seed();
        var snapshot = valuationRepository.findLatestSnapshot();
        assertThat(snapshot.tradingDay()).isEqualTo(java.time.LocalDate.of(2026, 8, 27));
        assertThat(snapshot.peMedian()).isEqualByComparingTo("19.14");
    }

    @Test
    @Transactional
    void 查询行业估值与国债() {
        seed();
        var industries = valuationRepository.findIndustryValuationsByDay(java.time.LocalDate.of(2026, 8, 27));
        assertThat(industries).hasSize(1);
        assertThat(industries.get(0).industryName()).isEqualTo("银行");
        assertThat(valuationRepository.findAllTreasuryYields()).hasSize(1);
    }

    @Test
    @Transactional
    void 国债只返回10年期() {
        jdbcTemplate.update("INSERT INTO treasury_yield_curve(trading_day, term, yield) VALUES (?, ?, ?)",
                java.sql.Date.valueOf("2026-08-27"), "10Y", new java.math.BigDecimal("2.21"));
        jdbcTemplate.update("INSERT INTO treasury_yield_curve(trading_day, term, yield) VALUES (?, ?, ?)",
                java.sql.Date.valueOf("2026-08-27"), "3Y", new java.math.BigDecimal("1.80"));
        var yields = valuationRepository.findAllTreasuryYields();
        assertThat(yields).hasSize(1);
        assertThat(yields.get(0).yield10y()).isEqualByComparingTo("2.21");
    }

    @Test
    @Transactional
    void 查询全部快照() {
        seed();
        var snapshots = valuationRepository.findAllSnapshots();
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).peMedian()).isEqualByComparingTo("19.14");
        assertThat(snapshots.get(0).pbMedian()).isEqualByComparingTo("1.68");
    }

    @Test
    @Transactional
    void 按代码查询指数估值历史() {
        seed();
        var indexVals = valuationRepository.findIndexValuations("000300");
        assertThat(indexVals).hasSize(1);
        assertThat(indexVals.get(0).indexName()).isEqualTo("沪深300");
        assertThat(indexVals.get(0).dividendYield()).isEqualByComparingTo("2.35");
    }

    @Test
    @Transactional
    void 查询全部申万行业映射() {
        jdbcTemplate.update("INSERT INTO shenwan_industry_mapping(stock_code, stock_name, industry_code, industry_name) VALUES (?, ?, ?, ?)",
                "600000", "浦发银行", "801780", "银行");
        var mappings = valuationRepository.findAllIndustryMappings();
        assertThat(mappings).hasSize(1);
        assertThat(mappings.get(0).stockCode()).isEqualTo("600000");
        assertThat(mappings.get(0).stockName()).isEqualTo("浦发银行");
        assertThat(mappings.get(0).industryName()).isEqualTo("银行");
    }

    @Test
    @Transactional
    void 空表时最新快照为空() {
        assertThat(valuationRepository.findLatestSnapshot()).isNull();
    }
}

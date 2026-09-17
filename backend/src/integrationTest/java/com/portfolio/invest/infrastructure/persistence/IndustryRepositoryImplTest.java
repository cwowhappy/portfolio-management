package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.industry.IndustryRepository;
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
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(IndustryRepositoryImpl.class)
class IndustryRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private IndustryRepository repository;

    @DisplayName("最新行业估值行 + 升序历史序列 + 申万映射存在性")
    @Test
    @Transactional
    @Sql(statements = {
        "INSERT INTO industry_valuation (trading_day, industry_code, industry_name, pe, pb, roe, dividend_yield) VALUES"
                + " ('2026-01-02','801780','银行',5.5,0.8,12.0,4.0),('2026-01-02','801010','农林牧渔',20.0,2.0,NULL,NULL)",
        "INSERT INTO industry_valuation (trading_day, industry_code, industry_name, pe, pb, roe, dividend_yield) VALUES ('2026-01-01','801780','银行',5.0,0.7,11.0,3.5)",
        "INSERT INTO shenwan_industry_mapping (stock_code, stock_name, industry_code, industry_name) VALUES ('600519','贵州茅台','801780','银行')"
    }, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
    void givenSeededIndustryValuation_whenFindLatestHistoryExistence_thenAllReturned() {
        var latest = repository.findLatestIndustries();
        assertThat(latest).extracting("industryCode").containsExactlyInAnyOrder("801780", "801010");
        var bank = latest.stream().filter(r -> r.industryCode().equals("801780")).findFirst().orElseThrow();
        assertThat(bank.pe()).isEqualByComparingTo("5.5");

        var history = repository.findValuationHistorySince(LocalDate.of(2025, 1, 1));
        assertThat(history).hasSize(3); // 3 行 ≥ since：两行业最新日 + 银行前一日（brief 误写 2）
        assertThat(history.get(0).tradingDay()).isEqualTo(LocalDate.of(2026, 1, 1)); // 升序：最早日在首
        assertThat(history.get(history.size() - 1).tradingDay()).isEqualTo(LocalDate.of(2026, 1, 2));

        assertThat(repository.existsIndustry("801780")).isTrue();
        assertThat(repository.existsIndustry("999999")).isFalse();
    }

    @DisplayName("行业成员排名：最新交易日 + DISTINCT ON 最新报告期财务 + 不足8季不标注景气")
    @Test
    @Transactional
    @Sql(statements = {
        "INSERT INTO stock_valuation_daily (trading_day, stock_code, stock_name, pe_ttm, pb, dividend_yield, total_mv) VALUES"
                + " ('2026-01-02','601398','工商银行',6.0,0.6,5.0,200000000000),"
                + "        ('2026-01-02','600519','贵州茅台',25.0,8.0,3.0,180000000000),"
                + "        ('2026-01-01','601398','工商银行',6.1,0.61,5.0,190000000000)", // 非最新日，应被过滤
        "INSERT INTO stock_financial (report_date, stock_code, roe, revenue) VALUES"
                + " ('2025-12-31','601398',11.0,400000000000),('2025-09-30','601398',10.0,300000000000),"
                + " ('2025-12-31','600519',30.0,170000000000)",
        "INSERT INTO shenwan_industry_mapping (stock_code, stock_name, industry_code, industry_name) VALUES"
                + " ('601398','工商银行','801780','银行'),('600519','贵州茅台','801780','银行')"
    }, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
    void givenSeededIndustryMembers_whenFindIndustryStocks_thenLatestFinancialsRanked() {
        var stocks = repository.findIndustryStocks("801780", "total_mv", "DESC", 1000);
        assertThat(stocks).hasSize(2);
        assertThat(stocks.get(0).stockCode()).isEqualTo("601398"); // 市值降序
        assertThat(stocks.get(0).revenue()).isEqualByComparingTo("400000000000"); // DISTINCT ON 最新报告期
        assertThat(stocks.get(0).revenueReportDate()).isEqualTo(LocalDate.of(2025, 12, 31));
        // 601398 仅 2 季 ROE（不足 8 季→roe_delta null→不标注）；600519 仅 1 季，同样 null
        assertThat(stocks).allSatisfy(s -> assertThat(s.prosperity()).isNull());
    }
}

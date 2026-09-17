package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.industry.IndustryRepository;
import com.portfolio.invest.domain.industry.Prosperity;
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

    @DisplayName("行业景气聚合：8 季窗口 ROEΔ/营收增速中位数 + 样本数")
    @Test
    @Transactional
    @Sql(statements = {
        // 两只成员股：A（000001）近 4 季 roe=12、前 4 季 roe=10 → ROEΔ=+2、revenue_yoy=15；
        // B（000002）8 季同值 → ROEΔ=0、revenue_yoy=5 → 中位数 {1, 10}，样本 2
        // （brief 原种子 rn1..4 混入 10 会算出 recent=10.5→Δ=0.5，与注释意图 Δ=2 矛盾——按意图改为近 4 季同 12）
        "INSERT INTO stock_financial (report_date, stock_code, roe, revenue_yoy) VALUES"
                + " ('2025-06-30','000001',12.0,15.0),('2025-03-31','000001',12.0,15.0),"   // rn1..2
                + " ('2024-12-31','000001',12.0,15.0),('2024-09-30','000001',12.0,15.0),"   // rn3..4：recent=12
                + " ('2024-06-30','000001',10.0,15.0),('2024-03-31','000001',10.0,15.0),"   // rn5..6
                + " ('2023-12-31','000001',10.0,15.0),('2023-09-30','000001',10.0,15.0),"   // rn7..8：prior=10
                + " ('2025-06-30','000002',6.0,5.0),('2025-03-31','000002',6.0,5.0),"
                + " ('2024-12-31','000002',6.0,5.0),('2024-09-30','000002',6.0,5.0),"
                + " ('2024-06-30','000002',6.0,5.0),('2024-03-31','000002',6.0,5.0),"
                + " ('2023-12-31','000002',6.0,5.0),('2023-09-30','000002',6.0,5.0)",
        "INSERT INTO shenwan_industry_mapping (stock_code, stock_name, industry_code, industry_name) VALUES"
                + " ('000001','平安银行','801780','银行'),('000002','万科A','801780','银行')"
    }, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
    void givenSeededIndustryMembers_whenFindIndustryProsperity_thenMediansAggregated() {
        var snaps = repository.findIndustryProsperity();
        var bank = snaps.stream().filter(s -> s.industryCode().equals("801780")).findFirst().orElseThrow();
        assertThat(bank.roeDeltaMedian()).isEqualByComparingTo("1"); // median(2,0)=1
        assertThat(bank.revenueYoyMedian()).isEqualByComparingTo("10"); // median(15,5)=10
        assertThat(bank.sampleSize()).isEqualTo(2);
    }

    @DisplayName("不足 8 季不标注：prior 桶仅 2 季 → 该股 prosperity null 且不计入 sample_size/中位数")
    @Test
    @Transactional
    @Sql(statements = {
        // C（000005）8 季齐：recent=12、prior=10 → ROEΔ=+2、revenue_yoy=20 → UP，正常参与聚合；
        // D（000006）仅 6 季：recent 4 季齐（=4）、prior 桶只有 rn5..6 两季 → roe_prior NULL →
        // 旧实现（AVG 不设 COUNT 门槛）会取 prior=10 得 ROEΔ=−6 且标 DOWN——本用例钉住修复后行为
        "INSERT INTO stock_valuation_daily (trading_day, stock_code, stock_name, pe_ttm, pb, dividend_yield, total_mv) VALUES"
                + " ('2026-01-02','000005','八季齐',6.0,0.6,5.0,200000000000),"
                + "        ('2026-01-02','000006','六季股',8.0,0.8,4.0,100000000000)",
        "INSERT INTO stock_financial (report_date, stock_code, roe, revenue_yoy) VALUES"
                + " ('2025-06-30','000005',12.0,20.0),('2025-03-31','000005',12.0,NULL),"   // rn1..2
                + " ('2024-12-31','000005',12.0,NULL),('2024-09-30','000005',12.0,NULL),"   // rn3..4：recent=12
                + " ('2024-06-30','000005',10.0,NULL),('2024-03-31','000005',10.0,NULL),"   // rn5..6
                + " ('2023-12-31','000005',10.0,NULL),('2023-09-30','000005',10.0,NULL),"   // rn7..8：prior=10
                + " ('2025-06-30','000006',4.0,50.0),('2025-03-31','000006',4.0,NULL),"     // rn1..2
                + " ('2024-12-31','000006',4.0,NULL),('2024-09-30','000006',4.0,NULL),"     // rn3..4：recent=4
                + " ('2024-06-30','000006',10.0,NULL),('2024-03-31','000006',10.0,NULL)",   // rn5..6：prior 仅 2 季
        "INSERT INTO shenwan_industry_mapping (stock_code, stock_name, industry_code, industry_name) VALUES"
                + " ('000005','八季齐','801780','银行'),('000006','六季股','801780','银行')"
    }, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
    void givenMemberWithOnlySixQuarters_whenProsperityQueried_thenNotLabeledNorCounted() {
        var stocks = repository.findIndustryStocks("801780", "total_mv", "DESC", 1000);
        var full = stocks.stream().filter(s -> s.stockCode().equals("000005")).findFirst().orElseThrow();
        var partial = stocks.stream().filter(s -> s.stockCode().equals("000006")).findFirst().orElseThrow();
        assertThat(full.prosperity()).isEqualTo(Prosperity.UP); // 8 季齐照常标注
        assertThat(partial.prosperity()).isNull(); // prior 桶不足 4 季 → ROEΔ null → 不标注（revenue_yoy=50 非空，排除它作为 null 原因）

        var snaps = repository.findIndustryProsperity();
        var bank = snaps.stream().filter(s -> s.industryCode().equals("801780")).findFirst().orElseThrow();
        assertThat(bank.sampleSize()).isEqualTo(1); // 六季股不计入样本
        assertThat(bank.roeDeltaMedian()).isEqualByComparingTo("2"); // 中位数只含 000005 的 Δ=2（旧实现 median(2,-6)=-2）
    }
}

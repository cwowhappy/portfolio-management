package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.valuation.ValuationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class ValuationRepositoryImplTest extends IntegrationTestBase {

    @Autowired
    private ValuationRepository valuationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void seed() {
        jdbcTemplate.update("INSERT INTO valuation_snapshot(trading_day, pe_median, pb_median, net_breaker_count, net_breaker_ratio) VALUES (?, ?, ?, ?, ?)",
                java.sql.Date.valueOf("2026-08-27"), new java.math.BigDecimal("19.14"), new java.math.BigDecimal("1.68"), 220, new java.math.BigDecimal("0.0410"));
        jdbcTemplate.update("INSERT INTO treasury_yield(trading_day, yield_10y) VALUES (?, ?)",
                java.sql.Date.valueOf("2026-08-27"), new java.math.BigDecimal("2.21"));
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
}

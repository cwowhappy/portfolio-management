package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.screening.ScreeningCriteria;
import com.portfolio.invest.domain.screening.ScreeningRepository;
import com.portfolio.invest.domain.screening.SortDirection;
import com.portfolio.invest.domain.screening.StockScreeningResult;
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

import java.math.BigDecimal;
import java.sql.Date;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(ScreeningRepositoryImpl.class)
class ScreeningRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private ScreeningRepository screeningRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void seedValuation(String code, String name, String pe, String turnover) {
        jdbcTemplate.update("INSERT INTO stock_valuation_daily(trading_day, stock_code, stock_name, pe_ttm, pb, dividend_yield, total_mv, circ_mv, turnover_rate) VALUES (?,?,?,?,?,?,?,?,?)",
                Date.valueOf("2026-08-27"), code, name, new BigDecimal(pe), new BigDecimal("1.0"),
                new BigDecimal("2.0"), new BigDecimal("1000000000"), new BigDecimal("1000000000"),
                new BigDecimal(turnover));
    }

    private void seedFinancial(String code, String roe) {
        jdbcTemplate.update("INSERT INTO stock_financial(report_date, stock_code, roe, roa, gross_margin, debt_to_assets, current_ratio, revenue_yoy, netprofit_yoy) VALUES (?,?,?,?,?,?,?,?,?)",
                Date.valueOf("2026-06-30"), code, new BigDecimal(roe), new BigDecimal("10"),
                new BigDecimal("30"), new BigDecimal("40"), new BigDecimal("2"),
                new BigDecimal("10"), new BigDecimal("10"));
    }

    private void seedMapping(String code, String name, String industryCode, String industryName) {
        jdbcTemplate.update("INSERT INTO shenwan_industry_mapping(stock_code, stock_name, industry_code, industry_name) VALUES (?,?,?,?)",
                code, name, industryCode, industryName);
    }

    @Test
    @Transactional
    void 按PE与ROE组合筛选() {
        seedValuation("600519", "贵州茅台", "22.5", "0.35");
        seedValuation("601398", "工商银行", "5.6", "0.18");
        seedFinancial("600519", "24.5");
        seedFinancial("601398", "15.8");
        seedMapping("600519", "贵州茅台", "801120", "食品饮料");
        seedMapping("601398", "工商银行", "801780", "银行");

        var criteria = new ScreeningCriteria(new BigDecimal("20"), null, null,
                new BigDecimal("15"), null, null, null, null, null, null, null, null,
                null, "pe_ttm", SortDirection.ASC, 200);

        var results = screeningRepository.findStocks(criteria);
        assertThat(results).extracting(StockScreeningResult::stockCode).containsExactly("601398");
        assertThat(results.get(0).industryName()).isEqualTo("银行");
    }

    @Test
    @Transactional
    void 按行业筛选() {
        seedValuation("600519", "贵州茅台", "22.5", "0.35");
        seedValuation("601398", "工商银行", "5.6", "0.18");
        seedMapping("600519", "贵州茅台", "801120", "食品饮料");
        seedMapping("601398", "工商银行", "801780", "银行");

        var criteria = new ScreeningCriteria(null, null, null, null, null, null, null, null,
                null, null, null, null, "801780", "pe_ttm", SortDirection.ASC, 200);

        var results = screeningRepository.findStocks(criteria);
        assertThat(results).extracting(StockScreeningResult::stockCode).containsExactly("601398");
    }

    @Test
    @Transactional
    void 排序与上限生效() {
        seedValuation("600519", "贵州茅台", "22.5", "0.35");
        seedValuation("601398", "工商银行", "5.6", "0.18");
        seedValuation("000858", "五粮液", "18.2", "0.62");

        var criteria = new ScreeningCriteria(new BigDecimal("30"), null, null, null, null, null,
                null, null, null, null, null, null, null, "pe_ttm", SortDirection.DESC, 2);

        var results = screeningRepository.findStocks(criteria);
        assertThat(results).extracting(StockScreeningResult::stockCode).containsExactly("600519", "000858");
    }
}

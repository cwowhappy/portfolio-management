package com.portfolio.invest.bdd.steps;

import com.portfolio.invest.application.screening.ScreeningApplicationService;
import com.portfolio.invest.domain.screening.ScreeningCriteria;
import com.portfolio.invest.domain.screening.ScreeningException;
import com.portfolio.invest.domain.screening.SortDirection;
import io.cucumber.java.zh_cn.假如;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import java.math.BigDecimal;
import java.sql.Date;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

public class ScreeningSteps {

    @Autowired
    private ScreeningApplicationService screeningService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ScenarioContext ctx;

    @假如("个股基本面中有贵州茅台与工商银行")
    @Transactional
    public void 种子数据() {
        jdbcTemplate.update("INSERT INTO stock_valuation_daily(trading_day, stock_code, stock_name, pe_ttm, pb, dividend_yield, total_mv, circ_mv, turnover_rate) VALUES (?,?,?,?,?,?,?,?,?)",
                Date.valueOf("2026-08-27"), "600519", "贵州茅台", new BigDecimal("22.5"), new BigDecimal("7.8"),
                new BigDecimal("2.1"), new BigDecimal("2100000000000"), new BigDecimal("2100000000000"), new BigDecimal("0.35"));
        jdbcTemplate.update("INSERT INTO stock_valuation_daily(trading_day, stock_code, stock_name, pe_ttm, pb, dividend_yield, total_mv, circ_mv, turnover_rate) VALUES (?,?,?,?,?,?,?,?,?)",
                Date.valueOf("2026-08-27"), "601398", "工商银行", new BigDecimal("5.6"), new BigDecimal("0.62"),
                new BigDecimal("5.4"), new BigDecimal("2200000000000"), new BigDecimal("2100000000000"), new BigDecimal("0.18"));
        jdbcTemplate.update("INSERT INTO stock_financial(report_date, stock_code, roe, roa, gross_margin, debt_to_assets, current_ratio, revenue_yoy, netprofit_yoy) VALUES (?,?,?,?,?,?,?,?,?)",
                Date.valueOf("2026-06-30"), "600519", new BigDecimal("24.5"), new BigDecimal("18.2"),
                new BigDecimal("91.2"), new BigDecimal("21.3"), new BigDecimal("3.8"), new BigDecimal("16.8"), new BigDecimal("15.2"));
        jdbcTemplate.update("INSERT INTO stock_financial(report_date, stock_code, roe, roa, gross_margin, debt_to_assets, current_ratio, revenue_yoy, netprofit_yoy) VALUES (?,?,?,?,?,?,?,?,?)",
                Date.valueOf("2026-06-30"), "601398", new BigDecimal("15.8"), new BigDecimal("0.95"),
                new BigDecimal("0"), new BigDecimal("91.8"), new BigDecimal("0.9"), new BigDecimal("2.1"), new BigDecimal("1.8"));
        jdbcTemplate.update("INSERT INTO shenwan_industry_mapping(stock_code, stock_name, industry_code, industry_name) VALUES (?,?,?,?)",
                "600519", "贵州茅台", "801120", "食品饮料");
        jdbcTemplate.update("INSERT INTO shenwan_industry_mapping(stock_code, stock_name, industry_code, industry_name) VALUES (?,?,?,?)",
                "601398", "工商银行", "801780", "银行");
    }

    @当("用户按 PE-TTM 小于 {bigdecimal} 且 ROE 大于 {bigdecimal} 筛选")
    public void 组合筛选(BigDecimal peMax, BigDecimal roeMin) {
        var criteria = new ScreeningCriteria(peMax, null, null, roeMin, null, null, null, null,
                null, null, null, null, null, "pe_ttm", SortDirection.ASC, 200);
        ctx.setScreeningResults(screeningService.screen(criteria));
    }

    @当("用户不填任何条件直接筛选")
    public void 无条件筛选() {
        try {
            screeningService.screen(new ScreeningCriteria(null, null, null, null, null, null, null,
                    null, null, null, null, null, null, "pe_ttm", SortDirection.ASC, 200));
            ctx.setScreeningError(null);
        } catch (ScreeningException e) {
            ctx.setScreeningError(e);
        }
    }

    @那么("筛选结果应只包含 {string}")
    public void 结果断言(String code) {
        assertThat(ctx.getScreeningResults())
                .extracting(r -> r.stockCode())
                .containsExactly(code);
    }

    @那么("系统应拒绝并提示需要至少一个条件")
    public void 拒绝断言() {
        assertThat(ctx.getScreeningError()).isNotNull();
        assertThat(ctx.getScreeningError().code()).isEqualTo("SCREENING_NO_CONDITION");
    }
}

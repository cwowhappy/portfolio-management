package com.portfolio.invest.bdd.steps;

import com.portfolio.invest.application.industry.IndustryApplicationService;
import com.portfolio.invest.application.industry.UnlistedCompanyView;
import com.portfolio.invest.application.industry.UnlistedResearchApplicationService;
import com.portfolio.invest.domain.industry.IndustryException;
import io.cucumber.java.After;
import io.cucumber.java.zh_cn.假如;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import java.math.BigDecimal;
import java.sql.Date;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

public class IndustrySteps {

    /** 本场景策展种子行名前缀（@After 清账按此前缀删，永不触及 V19 迁移种子）。 */
    private static final String BDD_COMPANY_PREFIX = "BDD测试";

    @Autowired
    private IndustryApplicationService industryService;

    @Autowired
    private UnlistedResearchApplicationService unlistedService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ScenarioContext ctx;

    @假如("银行业中有中国银行与农业银行且中国银行市值更大")
    @Transactional
    public void 种子行业成员() {
        // 造数口径（对齐 ScreeningSteps 的既有事实，见其 2026-08-27 固定日种子）：
        // ① BDD 无场景级回滚，种子跨场景提交落库，且 stock_valuation_daily 的最新交易日
        //    （max(trading_day)）是 screening 与 industry 两域共享的全局口径——所有场景
        //    必须写同一个最新日，任一场景写更晚日期会把别场景的行挤出 max 口径。
        // ② shenwan_industry_mapping.stock_code 全表 UNIQUE，代码须与 ScreeningSteps 占用的
        //    600519/601398 错开；601398 也属 801780，故中国银行市值（2.5e12）须大于
        //    工商银行（2.2e12），场景执行顺序无论先后，第一名都是 601988。
        // ③ pe_ttm 取 26/25（> screening 场景的 pe 上限 20），即使无 stock_financial 行
        //    （roe NULL 天然被 roe>=15 过滤）也不会混入 screening 的筛选结果。
        jdbcTemplate.update("INSERT INTO stock_valuation_daily(trading_day, stock_code, stock_name, pe_ttm, pb, total_mv) VALUES (?,?,?,?,?,?)",
                Date.valueOf("2026-08-27"), "601988", "中国银行", new BigDecimal("26"), new BigDecimal("0.6"), new BigDecimal("2500000000000"));
        jdbcTemplate.update("INSERT INTO stock_valuation_daily(trading_day, stock_code, stock_name, pe_ttm, pb, total_mv) VALUES (?,?,?,?,?,?)",
                Date.valueOf("2026-08-27"), "601288", "农业银行", new BigDecimal("25"), new BigDecimal("0.7"), new BigDecimal("180000000000"));
        jdbcTemplate.update("INSERT INTO shenwan_industry_mapping(stock_code, stock_name, industry_code, industry_name) VALUES (?,?,?,?)",
                "601988", "中国银行", "801780", "银行");
        jdbcTemplate.update("INSERT INTO shenwan_industry_mapping(stock_code, stock_name, industry_code, industry_name) VALUES (?,?,?,?)",
                "601288", "农业银行", "801780", "银行");
    }

    @当("用户查看银行业成员排名")
    public void 查看银行业成员排名() {
        ctx.setIndustryResults(industryService.stocks("801780", "total_mv", "DESC", 1000));
    }

    @那么("排名第一的应是 {string}")
    public void 排名第一断言(String code) {
        assertThat(ctx.getIndustryResults()).isNotEmpty();
        assertThat(ctx.getIndustryResults().get(0).stockCode()).isEqualTo(code);
    }

    @假如("申万行业映射中不存在行业 {string}")
    public void 无该行业(String industryCode) {
        jdbcTemplate.update("DELETE FROM shenwan_industry_mapping WHERE industry_code = ?", industryCode);
    }

    @当("用户查看行业 {string} 成员排名")
    public void 查看成员排名(String industryCode) {
        try {
            industryService.stocks(industryCode, "total_mv", "DESC", 1000);
            ctx.setIndustryError(null);
        } catch (IndustryException e) {
            ctx.setIndustryError(e);
        }
    }

    @那么("系统应提示行业不存在")
    public void 未找到断言() {
        assertThat(ctx.getIndustryError()).isNotNull();
        assertThat(ctx.getIndustryError().code()).isEqualTo("INDUSTRY_NOT_FOUND");
    }

    /**
     * 公开查询策展名单（MS-10 P2）：种 industry_unlisted_company 自有行（V19 表），不碰
     * industry_valuation 共享口径。行业存在性依赖 shenwan_industry_mapping——插入自有
     * 占位映射行使场景与执行顺序解耦（stock_code 全表 UNIQUE，601997 未被任何场景占用；
     * 该股无 stock_valuation_daily 行，不影响成员排名口径）。
     */
    @假如("银行业有两家策展未上市企业且最新轮次分别为 B 轮与 D 轮")
    @Transactional
    public void 种子策展企业() {
        jdbcTemplate.update(
                "INSERT INTO shenwan_industry_mapping(stock_code, stock_name, industry_code, industry_name) VALUES (?,?,?,?)",
                "601997", "BDD策展占位", "801780", "银行");
        jdbcTemplate.update("""
                INSERT INTO industry_unlisted_company
                    (industry_code, company_name, segment, latest_round, last_funding_date, total_funding_yi, summary, source_note)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                "801780", BDD_COMPANY_PREFIX + "光子", "光模块", "D",
                Date.valueOf("2026-08-15"), new BigDecimal("80.00"), "800G 光模块厂商", "BDD 种子");
        jdbcTemplate.update("""
                INSERT INTO industry_unlisted_company
                    (industry_code, company_name, segment, latest_round, last_funding_date, total_funding_yi, summary, source_note)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                "801780", BDD_COMPANY_PREFIX + "华芯", "半导体设备", "B",
                Date.valueOf("2026-06-15"), new BigDecimal("12.50"), "刻蚀设备新锐", "BDD 种子");
    }

    @After
    public void 清理策展种子() {
        jdbcTemplate.update("DELETE FROM industry_unlisted_company WHERE company_name LIKE ?", BDD_COMPANY_PREFIX + "%");
    }

    @当("用户查看银行业未上市策展名单")
    public void 查看策展名单() {
        ctx.setUnlistedResults(unlistedService.companies("801780"));
    }

    @那么("名单首家应是 {string} 且轮次标签为 {string}")
    public void 策展名单断言(String companyName, String roundLabel) {
        assertThat(ctx.getUnlistedResults()).isNotEmpty();
        // 排序口径：lastFundingDate DESC——光子（2026-08-15）先于华芯（2026-06-15）
        UnlistedCompanyView first = ctx.getUnlistedResults().get(0);
        assertThat(first.companyName()).isEqualTo(companyName);
        assertThat(first.latestRound()).isEqualTo("D");
        assertThat(first.latestRoundLabel()).isEqualTo(roundLabel);
    }
}

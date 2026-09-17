package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.industry.IndustryProsperitySnapshot;
import com.portfolio.invest.domain.industry.IndustryRepository;
import com.portfolio.invest.domain.industry.IndustryStock;
import com.portfolio.invest.domain.industry.IndustryValuationPoint;
import com.portfolio.invest.domain.industry.IndustryValuationRow;
import com.portfolio.invest.domain.industry.Prosperity;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IndustryRepositoryImpl implements IndustryRepository {

    /** sortBy 白名单（M10-F03）：非白名单键 get 返回 null 会拼出非法 SQL——防御性故障，入参由应用层（Task 7）校验拦截。 */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "total_mv", "d.total_mv",
            "revenue", "f.revenue",
            "roe", "f.roe");

    /** 个股 8 季窗口 CTE（T5 成员排名 / T6 行业景气共用）：近 4 季与前 4 季 ROE 均值 + 最新季营收同比。两桶各要求桶内 COUNT(roe)=4 才输出均值，否则 NULL——ROE 不足 8 季（或桶内 roe 缺值）的个股不标注、不计入 ROEΔ 中位数与 sample_size（规格 §三.C）。 */
    private static final String PER_STOCK_CTE = """
            WITH per_stock AS (
                SELECT stock_code,
                       CASE WHEN COUNT(roe) FILTER (WHERE rn <= 4) = 4
                            THEN AVG(roe) FILTER (WHERE rn <= 4) END AS roe_recent,
                       CASE WHEN COUNT(roe) FILTER (WHERE rn BETWEEN 5 AND 8) = 4
                            THEN AVG(roe) FILTER (WHERE rn BETWEEN 5 AND 8) END AS roe_prior,
                       MAX(revenue_yoy) FILTER (WHERE rn = 1) AS revenue_yoy
                FROM (SELECT stock_code, roe, revenue_yoy,
                             ROW_NUMBER() OVER (PARTITION BY stock_code ORDER BY report_date DESC) AS rn
                      FROM stock_financial) t
                GROUP BY stock_code
            )
            """;

    private final JdbcTemplate jdbc;

    public IndustryRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<IndustryValuationRow> findLatestIndustries() {
        var sql = """
                SELECT industry_code, industry_name, pe, pb, roe, dividend_yield
                FROM industry_valuation
                WHERE trading_day = (SELECT max(trading_day) FROM industry_valuation)
                """;
        return jdbc.query(sql, (rs, i) -> new IndustryValuationRow(
                rs.getString("industry_code"), rs.getString("industry_name"),
                rs.getBigDecimal("pe"), rs.getBigDecimal("pb"),
                rs.getBigDecimal("roe"), rs.getBigDecimal("dividend_yield")));
    }

    @Override
    public List<IndustryValuationPoint> findValuationHistorySince(LocalDate since) {
        var sql = """
                SELECT trading_day, industry_code, pe, pb
                FROM industry_valuation WHERE trading_day >= ? ORDER BY trading_day
                """;
        return jdbc.query(sql, (rs, i) -> new IndustryValuationPoint(
                rs.getDate("trading_day").toLocalDate(), rs.getString("industry_code"),
                rs.getBigDecimal("pe"), rs.getBigDecimal("pb")), since);
    }

    @Override
    public boolean existsIndustry(String industryCode) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM shenwan_industry_mapping WHERE industry_code = ?)",
                Boolean.class, industryCode);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public List<IndustryStock> findIndustryStocks(String industryCode, String sortBy, String direction, int limit) {
        // 文本块每行尾部空白会被剥离，ORDER BY 后的空格须由拼接串显式提供
        var sql = PER_STOCK_CTE + """
                SELECT d.stock_code, d.stock_name, d.total_mv, d.pe_ttm, d.pb, d.dividend_yield,
                       f.roe, f.revenue, f.report_date AS revenue_report_date,
                       (p.roe_recent - p.roe_prior) AS roe_delta, p.revenue_yoy
                FROM stock_valuation_daily d
                LEFT JOIN (
                    SELECT DISTINCT ON (stock_code) stock_code, roe, revenue, report_date
                    FROM stock_financial ORDER BY stock_code, report_date DESC
                ) f ON d.stock_code = f.stock_code
                LEFT JOIN per_stock p ON d.stock_code = p.stock_code
                JOIN shenwan_industry_mapping m ON d.stock_code = m.stock_code
                WHERE d.trading_day = (SELECT max(trading_day) FROM stock_valuation_daily)
                  AND m.industry_code = ?
                ORDER BY""" + " " + SORT_COLUMNS.get(sortBy) + " " + direction + " NULLS LAST LIMIT ?";
        return jdbc.query(sql, (rs, i) -> new IndustryStock(
                rs.getString("stock_code"), rs.getString("stock_name"),
                rs.getBigDecimal("total_mv"), rs.getBigDecimal("revenue"),
                rs.getDate("revenue_report_date") == null ? null : rs.getDate("revenue_report_date").toLocalDate(),
                rs.getBigDecimal("roe"), rs.getBigDecimal("pe_ttm"), rs.getBigDecimal("pb"),
                rs.getBigDecimal("dividend_yield"),
                Prosperity.of(rs.getBigDecimal("roe_delta"), rs.getBigDecimal("revenue_yoy"))),
                industryCode, limit);
    }

    @Override
    public List<IndustryProsperitySnapshot> findIndustryProsperity() {
        var sql = PER_STOCK_CTE + """
                SELECT m.industry_code,
                       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY (p.roe_recent - p.roe_prior)) AS roe_delta_median,
                       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY p.revenue_yoy) AS revenue_yoy_median,
                       COUNT(p.stock_code) FILTER (WHERE p.roe_recent IS NOT NULL AND p.roe_prior IS NOT NULL) AS sample_size
                FROM per_stock p JOIN shenwan_industry_mapping m ON m.stock_code = p.stock_code
                GROUP BY m.industry_code
                """;
        return jdbc.query(sql, (rs, i) -> new IndustryProsperitySnapshot(
                rs.getString("industry_code"), rs.getBigDecimal("roe_delta_median"),
                rs.getBigDecimal("revenue_yoy_median"), rs.getLong("sample_size")));
    }
}

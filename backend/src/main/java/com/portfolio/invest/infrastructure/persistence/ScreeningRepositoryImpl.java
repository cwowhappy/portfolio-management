package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.screening.FundScreeningCriteria;
import com.portfolio.invest.domain.screening.FundScreeningResult;
import com.portfolio.invest.domain.screening.ScreeningCriteria;
import com.portfolio.invest.domain.screening.ScreeningRepository;
import com.portfolio.invest.domain.screening.StockSearchHit;
import com.portfolio.invest.domain.screening.StockScreeningResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 筛选域仓库实现（stock_valuation_daily 最新快照日 LEFT JOIN 财务/行业维度；ETF 走 etf_basic 目录）。
 *
 * <p>动态 where 装配收敛于 {@link WhereClauseBuilder}（2026-10-16 第五轮审查扩簇归一，
 * 原局部 {@code and()} helper + StringBuilder 誊写簇）：可空条件逐字段追加、参数顺序与
 * 占位符一致，产出 SQL 与参数序列和原装配逐字等价。排序尾巴（{@code ORDER BY … NULLS
 * LAST LIMIT ?}）非 D20 分页双查询形态，不走 {@link WhereClauseBuilder#paged}，按原样
 * 拼在 {@link WhereClauseBuilder#sql()} 之后。用户输入绝不拼接进 SQL 文本——全部经
 * {@code ?} 占位参数绑定（表名/列名/固定短语均为编译期常量）。
 */
@Repository
public class ScreeningRepositoryImpl implements ScreeningRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.ofEntries(
            Map.entry("pe_ttm", "d.pe_ttm"),
            Map.entry("pb", "d.pb"),
            Map.entry("dividend_yield", "d.dividend_yield"),
            Map.entry("roe", "f.roe"),
            Map.entry("roa", "f.roa"),
            Map.entry("gross_margin", "f.gross_margin"),
            Map.entry("debt_to_assets", "f.debt_to_assets"),
            Map.entry("current_ratio", "f.current_ratio"),
            Map.entry("revenue_yoy", "f.revenue_yoy"),
            Map.entry("netprofit_yoy", "f.netprofit_yoy"),
            Map.entry("total_mv", "d.total_mv"),
            Map.entry("turnover_rate", "d.turnover_rate"));

    private static final String BASE_SQL = """
            SELECT d.stock_code, d.stock_name, m.industry_code, m.industry_name,
                   d.pe_ttm, d.pb, d.dividend_yield,
                   f.roe, f.roa, f.gross_margin, f.debt_to_assets, f.current_ratio,
                   f.revenue_yoy, f.netprofit_yoy, d.total_mv, d.turnover_rate,
                   d.trading_day
            FROM stock_valuation_daily d
            LEFT JOIN (
                SELECT DISTINCT ON (stock_code) stock_code, roe, roa, gross_margin,
                       debt_to_assets, current_ratio, revenue_yoy, netprofit_yoy
                FROM stock_financial ORDER BY stock_code, report_date DESC
            ) f ON d.stock_code = f.stock_code
            LEFT JOIN shenwan_industry_mapping m ON d.stock_code = m.stock_code
            WHERE d.trading_day = (SELECT max(trading_day) FROM stock_valuation_daily)
            """;

    private static final Map<String, String> FUND_SORT_COLUMNS = Map.of(
            "fee_rate", "fee_rate",
            "scale", "scale",
            "tracking_error_1y", "tracking_error_1y");

    /** etf_basic 无快照日维度；WHERE true 仅为动态 AND 拼接提供锚点（WhereClauseBuilder 归一）。 */
    private static final String FUND_BASE_SQL = """
            SELECT fund_code, fund_name, fee_rate, scale, tracking_index_name, category, tracking_error_1y
            FROM etf_basic
            WHERE true
            """;

    private final JdbcTemplate jdbc;

    public ScreeningRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<StockScreeningResult> findStocks(ScreeningCriteria c) {
        // 动态过滤（可空条件逐一追加，参数顺序与占位符一致）——装配骨架收敛于 WhereClauseBuilder
        WhereClauseBuilder where = WhereClauseBuilder.where(BASE_SQL)
                .andIfNotNull("d.pe_ttm", "<=", c.peTtmMax())
                .andIfNotNull("d.pb", "<=", c.pbMax())
                .andIfNotNull("d.dividend_yield", ">=", c.dividendYieldMin())
                .andIfNotNull("f.roe", ">=", c.roeMin())
                .andIfNotNull("f.roa", ">=", c.roaMin())
                .andIfNotNull("f.gross_margin", ">=", c.grossMarginMin())
                .andIfNotNull("f.debt_to_assets", "<=", c.debtToAssetsMax())
                .andIfNotNull("f.current_ratio", ">=", c.currentRatioMin())
                .andIfNotNull("f.revenue_yoy", ">=", c.revenueYoyMin())
                .andIfNotNull("f.netprofit_yoy", ">=", c.netprofitYoyMin())
                .andIfNotNull("d.total_mv", ">=", c.totalMvMin())
                .andIfNotNull("d.turnover_rate", ">=", c.turnoverRateMin())
                .andIfNotNull("m.industry_code", c.industryCode())
                .andIfNotNullPhrase(
                        "d.stock_code IN (SELECT stock_code FROM index_constituent WHERE index_code = ?)",
                        c.indexCode());

        String sql = where.sql() + " ORDER BY " + SORT_COLUMNS.get(c.sortBy()) + " "
                + c.sortDirection().name() + " NULLS LAST LIMIT ?";
        List<Object> args = where.args();
        args.add(c.limit());
        return jdbc.query(sql, ROW_MAPPER, args.toArray());
    }

    @Override
    public List<FundScreeningResult> findFunds(FundScreeningCriteria c) {
        // nullable 列直接数值比较：null 行不命中（「未知值不命中数值条件」，与 findStocks 同口径）
        WhereClauseBuilder where = WhereClauseBuilder.where(FUND_BASE_SQL)
                .andIfNotNull("fee_rate", "<=", c.feeRateMax())
                .andIfNotNull("scale", ">=", c.scaleMin())
                .andIfNotNull("tracking_error_1y", "<=", c.trackingErrorMax())
                .andIfNotNull("category", c.category());

        String sql = where.sql() + " ORDER BY " + FUND_SORT_COLUMNS.get(c.sortBy()) + " "
                + c.sortDirection().name() + " NULLS LAST LIMIT ?";
        List<Object> args = where.args();
        args.add(c.limit());
        return jdbc.query(sql, FUND_ROW_MAPPER, args.toArray());
    }

    @Override
    public List<StockScreeningResult> findStocksByCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        WhereClauseBuilder where = WhereClauseBuilder.where(BASE_SQL)
                .andIn("d.stock_code", codes);
        return jdbc.query(where.sql() + " ORDER BY d.stock_code", ROW_MAPPER, where.args().toArray());
    }

    @Override
    public boolean existsFund(String fundCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM etf_basic WHERE fund_code = ?", Integer.class, fundCode);
        return count != null && count > 0;
    }

    @Override
    public List<StockSearchHit> searchLatestSnapshot(String keyword, int limit) {
        // B7：关键字先字面化（%/_ 不再作通配符），LIKE 显式声明反斜杠转义符
        String kw = escapeLike(keyword);
        String sql = """
                SELECT d.stock_code, d.stock_name, m.industry_name, d.pe_ttm, d.pb, d.total_mv
                FROM stock_valuation_daily d
                LEFT JOIN shenwan_industry_mapping m ON d.stock_code = m.stock_code
                WHERE d.trading_day = (SELECT max(trading_day) FROM stock_valuation_daily)
                  AND (d.stock_code LIKE ? ESCAPE '\\' OR d.stock_name LIKE ? ESCAPE '\\')
                ORDER BY d.stock_code LIMIT ?
                """;
        return jdbc.query(sql, (rs, i) -> new StockSearchHit(
                        rs.getString("stock_code"), rs.getString("stock_name"), rs.getString("industry_name"),
                        rs.getBigDecimal("pe_ttm"), rs.getBigDecimal("pb"), rs.getBigDecimal("total_mv")),
                kw + "%", "%" + kw + "%", limit);
    }

    /**
     * LIKE 关键字字面化：先转义反斜杠本身，再转义 % 与 _（顺序不可反——
     * 先转 %/_ 会把刚引入的转义符再翻倍）。包私有以便纯单测。
     */
    static String escapeLike(String kw) {
        return kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 全维度行映射（findStocks / findStocksByCodes 共用）。 */
    private static final org.springframework.jdbc.core.RowMapper<StockScreeningResult> ROW_MAPPER = (rs, i) ->
            new StockScreeningResult(
                    rs.getString("stock_code"), rs.getString("stock_name"),
                    rs.getString("industry_code"), rs.getString("industry_name"),
                    rs.getBigDecimal("pe_ttm"), rs.getBigDecimal("pb"), rs.getBigDecimal("dividend_yield"),
                    rs.getBigDecimal("roe"), rs.getBigDecimal("roa"), rs.getBigDecimal("gross_margin"),
                    rs.getBigDecimal("debt_to_assets"), rs.getBigDecimal("current_ratio"),
                    rs.getBigDecimal("revenue_yoy"), rs.getBigDecimal("netprofit_yoy"),
                    rs.getBigDecimal("total_mv"), rs.getBigDecimal("turnover_rate"),
                    isoDate(rs, "trading_day"));

    /** DATE 列 → ISO yyyy-MM-dd 字符串（null 透传；LocalDate.toString 恒 ISO）。 */
    private static String isoDate(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate().toString();
    }

    /** ETF 目录行映射（findFunds 专用）。 */
    private static final org.springframework.jdbc.core.RowMapper<FundScreeningResult> FUND_ROW_MAPPER = (rs, i) ->
            new FundScreeningResult(
                    rs.getString("fund_code"), rs.getString("fund_name"),
                    rs.getBigDecimal("fee_rate"), rs.getBigDecimal("scale"),
                    rs.getString("tracking_index_name"), rs.getString("category"),
                    rs.getBigDecimal("tracking_error_1y"));
}

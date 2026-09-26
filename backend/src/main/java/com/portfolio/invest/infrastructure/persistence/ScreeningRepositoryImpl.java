package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.screening.FundScreeningCriteria;
import com.portfolio.invest.domain.screening.FundScreeningResult;
import com.portfolio.invest.domain.screening.ScreeningCriteria;
import com.portfolio.invest.domain.screening.ScreeningRepository;
import com.portfolio.invest.domain.screening.StockSearchHit;
import com.portfolio.invest.domain.screening.StockScreeningResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
                   f.revenue_yoy, f.netprofit_yoy, d.total_mv, d.turnover_rate
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

    /** etf_basic 无快照日维度；WHERE true 仅为下方动态 and() 拼接提供锚点。 */
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
        StringBuilder sql = new StringBuilder(BASE_SQL);
        List<Object> args = new ArrayList<>();
        and(sql, args, "d.pe_ttm <= ?", c.peTtmMax());
        and(sql, args, "d.pb <= ?", c.pbMax());
        and(sql, args, "d.dividend_yield >= ?", c.dividendYieldMin());
        and(sql, args, "f.roe >= ?", c.roeMin());
        and(sql, args, "f.roa >= ?", c.roaMin());
        and(sql, args, "f.gross_margin >= ?", c.grossMarginMin());
        and(sql, args, "f.debt_to_assets <= ?", c.debtToAssetsMax());
        and(sql, args, "f.current_ratio >= ?", c.currentRatioMin());
        and(sql, args, "f.revenue_yoy >= ?", c.revenueYoyMin());
        and(sql, args, "f.netprofit_yoy >= ?", c.netprofitYoyMin());
        and(sql, args, "d.total_mv >= ?", c.totalMvMin());
        and(sql, args, "d.turnover_rate >= ?", c.turnoverRateMin());
        and(sql, args, "m.industry_code = ?", c.industryCode());
        and(sql, args, "d.stock_code IN (SELECT stock_code FROM index_constituent WHERE index_code = ?)", c.indexCode());

        sql.append(" ORDER BY ").append(SORT_COLUMNS.get(c.sortBy())).append(" ")
                .append(c.sortDirection().name()).append(" NULLS LAST LIMIT ?");
        args.add(c.limit());

        return jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    @Override
    public List<FundScreeningResult> findFunds(FundScreeningCriteria c) {
        StringBuilder sql = new StringBuilder(FUND_BASE_SQL);
        List<Object> args = new ArrayList<>();
        // nullable 列直接数值比较：null 行不命中（「未知值不命中数值条件」，与 findStocks 同口径）
        and(sql, args, "fee_rate <= ?", c.feeRateMax());
        and(sql, args, "scale >= ?", c.scaleMin());
        and(sql, args, "tracking_error_1y <= ?", c.trackingErrorMax());
        and(sql, args, "category = ?", c.category());

        sql.append(" ORDER BY ").append(FUND_SORT_COLUMNS.get(c.sortBy())).append(" ")
                .append(c.sortDirection().name()).append(" NULLS LAST LIMIT ?");
        args.add(c.limit());

        return jdbc.query(sql.toString(), FUND_ROW_MAPPER, args.toArray());
    }

    @Override
    public List<StockScreeningResult> findStocksByCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
        String sql = BASE_SQL + " AND d.stock_code IN (" + placeholders + ") ORDER BY d.stock_code";
        return jdbc.query(sql, ROW_MAPPER, codes.toArray());
    }

    @Override
    public boolean existsFund(String fundCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM etf_basic WHERE fund_code = ?", Integer.class, fundCode);
        return count != null && count > 0;
    }

    @Override
    public List<StockSearchHit> searchLatestSnapshot(String keyword, int limit) {
        String sql = """
                SELECT d.stock_code, d.stock_name, m.industry_name, d.pe_ttm, d.pb, d.total_mv
                FROM stock_valuation_daily d
                LEFT JOIN shenwan_industry_mapping m ON d.stock_code = m.stock_code
                WHERE d.trading_day = (SELECT max(trading_day) FROM stock_valuation_daily)
                  AND (d.stock_code LIKE ? OR d.stock_name LIKE ?)
                ORDER BY d.stock_code LIMIT ?
                """;
        return jdbc.query(sql, (rs, i) -> new StockSearchHit(
                        rs.getString("stock_code"), rs.getString("stock_name"), rs.getString("industry_name"),
                        rs.getBigDecimal("pe_ttm"), rs.getBigDecimal("pb"), rs.getBigDecimal("total_mv")),
                keyword + "%", "%" + keyword + "%", limit);
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
                    rs.getBigDecimal("total_mv"), rs.getBigDecimal("turnover_rate"));

    /** ETF 目录行映射（findFunds 专用）。 */
    private static final org.springframework.jdbc.core.RowMapper<FundScreeningResult> FUND_ROW_MAPPER = (rs, i) ->
            new FundScreeningResult(
                    rs.getString("fund_code"), rs.getString("fund_name"),
                    rs.getBigDecimal("fee_rate"), rs.getBigDecimal("scale"),
                    rs.getString("tracking_index_name"), rs.getString("category"),
                    rs.getBigDecimal("tracking_error_1y"));

    private void and(StringBuilder sql, List<Object> args, String clause, Object value) {
        if (value != null) {
            sql.append(" AND ").append(clause);
            args.add(value);
        }
    }
}

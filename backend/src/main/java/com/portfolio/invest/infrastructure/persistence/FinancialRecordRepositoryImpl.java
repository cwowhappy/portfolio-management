package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.market.FinancialRecord;
import com.portfolio.invest.domain.market.FinancialRecordRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FinancialRecordRepositoryImpl implements FinancialRecordRepository {

    private final JdbcTemplate jdbc;

    public FinancialRecordRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<FinancialRecord> findByCodeLatest(String stockCode, int limit) {
        return jdbc.query("""
                SELECT report_date, roe, roa, gross_margin, debt_to_assets, current_ratio,
                       revenue_yoy, netprofit_yoy, revenue
                  FROM stock_financial
                 WHERE stock_code = ?
                 ORDER BY report_date DESC
                 LIMIT ?
                """,
                (rs, i) -> new FinancialRecord(
                        rs.getDate("report_date").toLocalDate(),
                        num(rs, "roe"),
                        num(rs, "roa"),
                        num(rs, "gross_margin"),
                        num(rs, "debt_to_assets"),
                        num(rs, "current_ratio"),
                        num(rs, "revenue_yoy"),
                        num(rs, "netprofit_yoy"),
                        rs.getBigDecimal("revenue")),
                stockCode, limit);
    }

    /** PG NUMERIC → Double（NULL 安全；PG 驱动不支持 getObject(col, Double.class) 直转 numeric）。 */
    private static Double num(ResultSet rs, String col) throws SQLException {
        BigDecimal v = rs.getBigDecimal(col);
        return v == null ? null : v.doubleValue();
    }
}

package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.application.alert.StockMetric;
import com.portfolio.invest.application.alert.ValuationDailyPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Repository
public class ValuationDailyAdapter implements ValuationDailyPort {

    private final JdbcTemplate jdbc;

    public ValuationDailyAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LocalDate> latestTradingDay() {
        Date latest = jdbc.queryForObject("SELECT max(trading_day) FROM stock_valuation_daily", Date.class);
        return Optional.ofNullable(latest).map(Date::toLocalDate);
    }

    @Override
    public Map<String, StockMetric> snapshots(LocalDate tradingDay, Collection<String> stockCodes) {
        if (stockCodes.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(stockCodes.size(), "?"));
        Object[] args = new Object[stockCodes.size() + 1];
        args[0] = tradingDay;
        int i = 1;
        for (String code : stockCodes) {
            args[i++] = code;
        }
        Map<String, StockMetric> out = new HashMap<>();
        jdbc.query("SELECT stock_code, stock_name, pe_ttm, pb, close FROM stock_valuation_daily "
                        + "WHERE trading_day = ? AND stock_code IN (" + placeholders + ")",
                rs -> {
                    out.put(rs.getString("stock_code"), new StockMetric(rs.getString("stock_code"),
                            rs.getString("stock_name"), getBigDecimal(rs, "close"),
                            getBigDecimal(rs, "pe_ttm"), getBigDecimal(rs, "pb")));
                }, args);
        return out;
    }

    private static BigDecimal getBigDecimal(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        BigDecimal v = rs.getBigDecimal(column);
        return v == null || rs.wasNull() ? null : v;
    }
}

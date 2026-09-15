package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.analytics.StockClosePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.SortedMap;
import java.util.TreeMap;

@Repository
public class StockCloseAdapter implements StockClosePort {

    private final JdbcTemplate jdbc;

    public StockCloseAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SortedMap<LocalDate, BigDecimal> closes(String stockCode, LocalDate from, LocalDate to) {
        SortedMap<LocalDate, BigDecimal> out = new TreeMap<>();
        jdbc.query("SELECT trading_day, close FROM stock_valuation_daily "
                        + "WHERE stock_code = ? AND trading_day BETWEEN ? AND ? AND close IS NOT NULL "
                        + "ORDER BY trading_day",
                rs -> {
                    out.put(rs.getDate("trading_day").toLocalDate(), rs.getBigDecimal("close"));
                },
                stockCode, from, to);
        return out;
    }
}

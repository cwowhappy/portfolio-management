package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.analytics.IndexClosePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.SortedMap;
import java.util.TreeMap;

@Repository
public class IndexCloseAdapter implements IndexClosePort {

    private final JdbcTemplate jdbc;

    public IndexCloseAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SortedMap<LocalDate, BigDecimal> closes(String indexCode, LocalDate from, LocalDate to) {
        SortedMap<LocalDate, BigDecimal> out = new TreeMap<>();
        jdbc.query("SELECT trading_day, close FROM index_close_history "
                        + "WHERE index_code = ? AND trading_day BETWEEN ? AND ? AND close IS NOT NULL "
                        + "ORDER BY trading_day",
                rs -> {
                    out.put(rs.getDate("trading_day").toLocalDate(), rs.getBigDecimal("close"));
                },
                indexCode, from, to);
        return out;
    }
}

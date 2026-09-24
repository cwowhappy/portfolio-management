package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.analytics.RiskFreeRatePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.SortedMap;
import java.util.TreeMap;

@Repository
public class RiskFreeRateAdapter implements RiskFreeRatePort {

    private final JdbcTemplate jdbc;

    public RiskFreeRateAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SortedMap<LocalDate, BigDecimal> oneYearSeries(LocalDate from, LocalDate to) {
        SortedMap<LocalDate, BigDecimal> out = new TreeMap<>();
        jdbc.query("SELECT trading_day, yield FROM treasury_yield_curve "
                        + "WHERE term = '1Y' AND trading_day BETWEEN ? AND ? ORDER BY trading_day",
                rs -> {
                    out.put(rs.getDate("trading_day").toLocalDate(), rs.getBigDecimal("yield"));
                },
                from, to);
        return out;
    }
}

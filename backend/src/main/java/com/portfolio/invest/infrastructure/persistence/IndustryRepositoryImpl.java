package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.industry.IndustryProsperitySnapshot;
import com.portfolio.invest.domain.industry.IndustryRepository;
import com.portfolio.invest.domain.industry.IndustryStock;
import com.portfolio.invest.domain.industry.IndustryValuationPoint;
import com.portfolio.invest.domain.industry.IndustryValuationRow;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IndustryRepositoryImpl implements IndustryRepository {

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
        // Task 5 实现
        throw new UnsupportedOperationException("Task 5/6 实现");
    }

    @Override
    public List<IndustryProsperitySnapshot> findIndustryProsperity() {
        // Task 6 实现
        throw new UnsupportedOperationException("Task 5/6 实现");
    }
}

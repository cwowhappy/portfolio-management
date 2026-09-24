package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.analytics.BenchmarkIndustryWeightPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class BenchmarkIndustryWeightAdapter implements BenchmarkIndustryWeightPort {

    private final JdbcTemplate jdbc;

    public BenchmarkIndustryWeightAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, BigDecimal> industryWeights(String indexCode) {
        // 成分股（快照，weight 百分数）JOIN 行业映射按行业求和；未映射成分股并入 UNMAPPED；
        // weight 可空（V4 DDL 无 NOT NULL），组内全 NULL 时 SUM 返回 SQL NULL → COALESCE 0 兜底，
        // 否则 HashMap.merge(null) NPE → attribution 500（部分 NULL 已由 SUM 语义忽略）
        Map<String, BigDecimal> percent = new LinkedHashMap<>();
        jdbc.query("""
                SELECT COALESCE(m.industry_code, ?) AS industry, COALESCE(SUM(c.weight), 0) AS w
                FROM index_constituent c
                LEFT JOIN shenwan_industry_mapping m ON c.stock_code = m.stock_code
                WHERE c.index_code = ?
                GROUP BY 1
                """,
                rs -> {
                    percent.merge(rs.getString("industry"), rs.getBigDecimal("w"), BigDecimal::add);
                },
                UNMAPPED_KEY, indexCode);
        BigDecimal total = percent.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (total.signum() <= 0) {
            return out;
        }
        percent.forEach((k, v) -> out.put(k, v.divide(total, MathContext.DECIMAL64)
                .setScale(10, RoundingMode.HALF_UP)));
        return out;
    }
}

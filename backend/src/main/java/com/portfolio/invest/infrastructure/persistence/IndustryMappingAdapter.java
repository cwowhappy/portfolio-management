package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.analytics.IndustryMappingPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

@Repository
public class IndustryMappingAdapter implements IndustryMappingPort {

    private final JdbcTemplate jdbc;

    public IndustryMappingAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, IndustryRef> byStock() {
        Map<String, IndustryRef> out = new HashMap<>();
        jdbc.query("SELECT stock_code, industry_code, industry_name FROM shenwan_industry_mapping",
                rs -> {
                    out.put(rs.getString("stock_code"),
                            new IndustryRef(rs.getString("industry_code"), rs.getString("industry_name")));
                });
        return out;
    }
}

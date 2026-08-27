package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.valuation.ShenwanIndustryMapping;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "shenwan_industry_mapping")
public class ShenwanIndustryMappingJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false)
    private String stockCode;

    @Column(name = "stock_name", nullable = false)
    private String stockName;

    @Column(name = "industry_code", nullable = false)
    private String industryCode;

    @Column(name = "industry_name", nullable = false)
    private String industryName;

    protected ShenwanIndustryMappingJpaEntity() {}

    public ShenwanIndustryMapping toDomain() {
        return new ShenwanIndustryMapping(stockCode, stockName, industryCode, industryName);
    }
}

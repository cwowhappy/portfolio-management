package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.valuation.IndustryValuation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "industry_valuation")
public class IndustryValuationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_day", nullable = false)
    private LocalDate tradingDay;

    @Column(name = "industry_code", nullable = false)
    private String industryCode;

    @Column(name = "industry_name", nullable = false)
    private String industryName;

    private BigDecimal pe;

    private BigDecimal pb;

    private BigDecimal roe;

    @Column(name = "dividend_yield")
    private BigDecimal dividendYield;

    protected IndustryValuationJpaEntity() {}

    public IndustryValuation toDomain() {
        return new IndustryValuation(tradingDay, industryCode, industryName, pe, pb, roe, dividendYield);
    }
}

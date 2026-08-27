package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.valuation.IndexValuation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "index_valuation_history")
public class IndexValuationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_day", nullable = false)
    private LocalDate tradingDay;

    @Column(name = "index_code", nullable = false)
    private String indexCode;

    @Column(name = "index_name", nullable = false)
    private String indexName;

    private BigDecimal pe;

    private BigDecimal pb;

    @Column(name = "dividend_yield")
    private BigDecimal dividendYield;

    protected IndexValuationJpaEntity() {}

    public IndexValuation toDomain() {
        return new IndexValuation(tradingDay, indexCode, indexName, pe, pb, dividendYield);
    }
}

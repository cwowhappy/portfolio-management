package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.valuation.TreasuryYield;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "treasury_yield_curve")
public class TreasuryYieldJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_day", nullable = false)
    private LocalDate tradingDay;

    @Column(name = "term", nullable = false)
    private String term;

    @Column(name = "yield", nullable = false)
    private BigDecimal yield;

    protected TreasuryYieldJpaEntity() {}

    public TreasuryYield toDomain() {
        return new TreasuryYield(tradingDay, yield);
    }
}

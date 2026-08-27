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
@Table(name = "treasury_yield")
public class TreasuryYieldJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_day", nullable = false)
    private LocalDate tradingDay;

    @Column(name = "yield_10y", nullable = false)
    private BigDecimal yield10y;

    protected TreasuryYieldJpaEntity() {}

    public TreasuryYield toDomain() {
        return new TreasuryYield(tradingDay, yield10y);
    }
}

package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.valuation.ValuationSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "valuation_snapshot")
public class ValuationSnapshotJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_day", nullable = false)
    private LocalDate tradingDay;

    @Column(name = "pe_median", nullable = false)
    private BigDecimal peMedian;

    @Column(name = "pb_median", nullable = false)
    private BigDecimal pbMedian;

    @Column(name = "net_breaker_count", nullable = false)
    private int netBreakerCount;

    @Column(name = "net_breaker_ratio", nullable = false)
    private BigDecimal netBreakerRatio;

    protected ValuationSnapshotJpaEntity() {}

    public ValuationSnapshot toDomain() {
        return new ValuationSnapshot(tradingDay, peMedian, pbMedian, netBreakerCount, netBreakerRatio);
    }
}

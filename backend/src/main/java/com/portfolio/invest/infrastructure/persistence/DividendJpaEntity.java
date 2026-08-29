package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.portfolio.Dividend;
import com.portfolio.invest.domain.portfolio.DividendType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "dividend")
public class DividendJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "position_id", nullable = false)
    private Long positionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DividendType type;

    @Column(name = "ex_date", nullable = false)
    private LocalDate exDate;

    @Column(name = "cash_per_share")
    private BigDecimal cashPerShare;

    @Column(name = "stock_ratio")
    private BigDecimal stockRatio;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DividendJpaEntity() {}

    public static DividendJpaEntity fromDomain(Dividend d) {
        DividendJpaEntity e = new DividendJpaEntity();
        e.id = d.id();
        e.positionId = d.positionId();
        e.type = d.type();
        e.exDate = d.exDate();
        e.cashPerShare = d.cashPerShare();
        e.stockRatio = d.stockRatio();
        e.createdAt = d.createdAt();
        return e;
    }

    public Dividend toDomain() {
        return new Dividend(id, positionId, type, exDate, cashPerShare, stockRatio, createdAt);
    }
}

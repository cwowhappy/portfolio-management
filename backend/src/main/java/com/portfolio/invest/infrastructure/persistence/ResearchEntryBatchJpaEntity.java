package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.EntryBatch;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** 建仓批次行（research_entry_batch）：随 plan 整替（先删后插，无独立生命周期）。 */
@Entity
@Table(name = "research_entry_batch")
public class ResearchEntryBatchJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(nullable = false)
    private int seq;

    @Column(name = "price_low", nullable = false, precision = 12, scale = 4)
    private BigDecimal priceLow;

    @Column(name = "price_high", nullable = false, precision = 12, scale = 4)
    private BigDecimal priceHigh;

    @Column(nullable = false)
    private long quantity;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 6, scale = 4)
    private BigDecimal ratio;

    protected ResearchEntryBatchJpaEntity() {}

    public static ResearchEntryBatchJpaEntity of(Long planId, EntryBatch b) {
        ResearchEntryBatchJpaEntity entity = new ResearchEntryBatchJpaEntity();
        entity.planId = planId;
        entity.seq = b.seq();
        entity.priceLow = b.priceLow();
        entity.priceHigh = b.priceHigh();
        entity.quantity = b.quantity();
        entity.amount = b.amount();
        entity.ratio = b.ratio();
        return entity;
    }

    public EntryBatch toDomain() {
        return new EntryBatch(seq, priceLow, priceHigh, quantity, amount, ratio);
    }
}

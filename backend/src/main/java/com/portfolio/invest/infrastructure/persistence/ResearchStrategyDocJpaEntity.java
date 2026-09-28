package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.StrategyDoc;
import com.portfolio.invest.domain.research.StrategyState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "research_strategy_doc")
public class ResearchStrategyDocJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, unique = true)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StrategyState state;

    @Column(columnDefinition = "text")
    private String thesis;

    @Column(name = "valuation_low", precision = 12, scale = 4)
    private BigDecimal valuationLow;

    @Column(name = "valuation_high", precision = 12, scale = 4)
    private BigDecimal valuationHigh;

    @Column(name = "position_plan", columnDefinition = "text")
    private String positionPlan;

    @Column(name = "buy_conditions", columnDefinition = "text")
    private String buyConditions;

    @Column(name = "risk_notes", columnDefinition = "text")
    private String riskNotes;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ResearchStrategyDocJpaEntity() {}

    public static ResearchStrategyDocJpaEntity fromDomain(StrategyDoc d) {
        ResearchStrategyDocJpaEntity entity = new ResearchStrategyDocJpaEntity();
        entity.id = d.id();
        entity.projectId = d.projectId();
        entity.state = d.state();
        entity.thesis = d.thesis();
        entity.valuationLow = d.valuationLow();
        entity.valuationHigh = d.valuationHigh();
        entity.positionPlan = d.positionPlan();
        entity.buyConditions = d.buyConditions();
        entity.riskNotes = d.riskNotes();
        entity.finalizedAt = d.finalizedAt();
        entity.createdAt = d.createdAt();
        entity.updatedAt = d.updatedAt();
        entity.version = d.version();
        return entity;
    }

    public StrategyDoc toDomain() {
        return StrategyDoc.reconstitute(id, projectId, state, thesis, valuationLow, valuationHigh,
                positionPlan, buyConditions, riskNotes, finalizedAt, version, createdAt, updatedAt);
    }
}

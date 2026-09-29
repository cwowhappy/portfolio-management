package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.EntryPlan;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "research_entry_plan")
public class ResearchEntryPlanJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** D23 凯利参数手动输入（可空=未估）。 */
    @Column(name = "win_rate", precision = 6, scale = 4)
    private BigDecimal winRate;

    @Column(name = "payoff_ratio", precision = 6, scale = 4)
    private BigDecimal payoffRatio;

    /** 系统算术结果冗余列：写入时取 domain kellyRatio()（读侧读时计算，列仅落库留档）。 */
    @Column(name = "kelly_ratio", precision = 6, scale = 4)
    private BigDecimal kellyRatio;

    /** V2 预留说明列（domain 无 note 字段，写入恒 null）。 */
    @Column(length = 500)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ResearchEntryPlanJpaEntity() {}

    public static ResearchEntryPlanJpaEntity fromDomain(EntryPlan p) {
        ResearchEntryPlanJpaEntity entity = new ResearchEntryPlanJpaEntity();
        entity.id = p.id();
        entity.projectId = p.projectId();
        entity.winRate = p.winRate();
        entity.payoffRatio = p.payoffRatio();
        entity.kellyRatio = p.kellyRatio();
        entity.note = null;
        entity.createdAt = p.createdAt();
        entity.updatedAt = p.updatedAt();
        entity.version = p.version();
        return entity;
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public BigDecimal getWinRate() { return winRate; }
    public BigDecimal getPayoffRatio() { return payoffRatio; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}

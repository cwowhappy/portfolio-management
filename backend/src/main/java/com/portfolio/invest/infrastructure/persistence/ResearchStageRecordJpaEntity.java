package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.ResearchStage;
import com.portfolio.invest.domain.research.StageCompletionService;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** research_stage_record：仅承载手动覆盖（S6），无 version 列，upsert 走 (project_id, stage) 唯一键。 */
@Entity
@Table(name = "research_stage_record")
public class ResearchStageRecordJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ResearchStage stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "manual_state", length = 16)
    private StageCompletionService.ManualState manualState; // NULL = 无覆盖

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ResearchStageRecordJpaEntity() {}

    static ResearchStageRecordJpaEntity of(Long projectId, ResearchStage stage,
                                           StageCompletionService.ManualState manualState, Instant updatedAt) {
        ResearchStageRecordJpaEntity entity = new ResearchStageRecordJpaEntity();
        entity.projectId = projectId;
        entity.stage = stage;
        entity.manualState = toColumnValue(manualState);
        entity.updatedAt = updatedAt;
        return entity;
    }

    /** upsert 时的覆盖写（纯承载变更，无业务语义）。 */
    void updateManualState(StageCompletionService.ManualState manualState, Instant updatedAt) {
        this.manualState = toColumnValue(manualState);
        this.updatedAt = updatedAt;
    }

    /**
     * {@link StageCompletionService.ManualState#NULL}（未标记）落列为 SQL NULL——
     * S6 仅存真实覆盖（COMPLETED/REOPENED）；枚举名恰为 "NULL"，若直存字符串会绕过
     * {@code manual_state IS NOT NULL} 过滤，读侧把「无覆盖」当覆盖带出。
     */
    private static StageCompletionService.ManualState toColumnValue(
            StageCompletionService.ManualState manualState) {
        return manualState == StageCompletionService.ManualState.NULL ? null : manualState;
    }

    ResearchStage stage() { return stage; }
    StageCompletionService.ManualState manualState() { return manualState; }
}

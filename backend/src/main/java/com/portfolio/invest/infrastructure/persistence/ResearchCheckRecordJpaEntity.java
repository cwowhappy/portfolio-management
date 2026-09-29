package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.CheckItemResult;
import com.portfolio.invest.domain.research.CheckRecord;
import com.portfolio.invest.domain.research.CheckResult;
import com.portfolio.invest.domain.research.CheckType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 纪律检查留痕行（research_check_record，append-only：无 version/updated_at、无 update 用例）。 */
@Entity
@Table(name = "research_check_record")
public class ResearchCheckRecordJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false, length = 16)
    private CheckType checkType;

    /** S4 检查项快照（List&lt;CheckItemResult&gt; JSONB，照 McpUserConfigJpaEntity.disabledTools 先例）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<CheckItemResult> items;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CheckResult result;

    @Column(name = "override_reason", length = 500)
    private String overrideReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ResearchCheckRecordJpaEntity() {}

    public static ResearchCheckRecordJpaEntity fromDomain(CheckRecord r) {
        ResearchCheckRecordJpaEntity entity = new ResearchCheckRecordJpaEntity();
        entity.id = r.id();
        entity.projectId = r.projectId();
        entity.checkType = r.checkType();
        entity.items = r.items();
        entity.result = r.result();
        entity.overrideReason = r.overrideReason();
        entity.createdAt = r.createdAt();
        return entity;
    }

    public CheckRecord toDomain() {
        return CheckRecord.reconstitute(id, projectId, checkType, items, result, overrideReason, createdAt);
    }
}

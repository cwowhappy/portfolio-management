package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.ReportAssumption;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 研报关键假设行（research_report_assumption，V8 建表，设计规格 §3.5）：
 * UNIQUE(report_id, seq) 由 V8 兜底；anchorId 可空 = 门未绑定假设（门语义见 §3.5）。
 */
@Entity
@Table(name = "research_report_assumption")
public class ReportAssumptionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, columnDefinition = "text")
    private String statement;

    @Column(name = "anchor_id")
    private Long anchorId;

    protected ReportAssumptionJpaEntity() {}

    /** reportId 以仓库 saveAll 入参为准（与 record 内值一致性由用例层保证）。 */
    public static ReportAssumptionJpaEntity fromDomain(Long reportId, ReportAssumption a) {
        ReportAssumptionJpaEntity entity = new ReportAssumptionJpaEntity();
        entity.id = a.id();
        entity.reportId = reportId;
        entity.seq = a.seq();
        entity.statement = a.statement();
        entity.anchorId = a.anchorId();
        return entity;
    }

    public ReportAssumption toDomain() {
        return new ReportAssumption(id, reportId, seq, statement, anchorId);
    }
}

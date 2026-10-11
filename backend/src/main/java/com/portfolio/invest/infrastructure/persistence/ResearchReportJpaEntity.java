package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.ResearchReport;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 研报行（research_report，V8 建表，设计规格 §3.10）：任务成功后的定稿文本
 * （校验门修正版，含 ⚠ 注记行）。表无 version 列——不加 @Version，
 * confirm/reject 并发争用由域状态机（终态再操作 empty）+ 应用层条件语义兜底。
 */
@Entity
@Table(name = "research_report")
public class ResearchReportJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ResearchReport.Status status;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(name = "content_md", nullable = false, columnDefinition = "text")
    private String contentMd;

    /** 数据局限注记（工具失败等），可空。 */
    @Column(columnDefinition = "text")
    private String limitations;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "rejected_reason", columnDefinition = "text")
    private String rejectedReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ResearchReportJpaEntity() {}

    public static ResearchReportJpaEntity fromDomain(ResearchReport r) {
        ResearchReportJpaEntity entity = new ResearchReportJpaEntity();
        entity.id = r.id();
        entity.projectId = r.projectId();
        entity.taskId = r.taskId();
        entity.userId = r.userId();
        entity.status = r.status();
        entity.title = r.title();
        entity.contentMd = r.contentMd();
        entity.limitations = r.limitations();
        entity.confirmedAt = r.confirmedAt();
        entity.rejectedReason = r.rejectedReason();
        entity.createdAt = r.createdAt();
        entity.updatedAt = r.updatedAt();
        return entity;
    }

    public ResearchReport toDomain() {
        return ResearchReport.reconstitute(id, taskId, projectId, userId, status, title,
                contentMd, limitations, confirmedAt, rejectedReason, createdAt, updatedAt);
    }
}

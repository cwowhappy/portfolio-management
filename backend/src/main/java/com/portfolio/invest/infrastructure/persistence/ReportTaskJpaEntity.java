package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.ReportTask;
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
 * 研报任务行（research_report_task，V8 建表，设计规格 §3.2）。同项目单活跃任务由部分唯一索引
 * uq_report_task_active 兜底（status IN ('PENDING','RUNNING') 才占索引位），终态行不占位、
 * 项目可再发起。表无 version 列——不加 @Version（照表结构，乐观并发需求由索引/条件更新承担）。
 */
@Entity
@Table(name = "research_report_task")
public class ReportTaskJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReportTask.Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReportTask.Stage stage;

    @Column(name = "token_usage", nullable = false)
    private long tokenUsage;

    @Column(name = "error_code", columnDefinition = "text")
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected ReportTaskJpaEntity() {}

    public static ReportTaskJpaEntity fromDomain(ReportTask t) {
        ReportTaskJpaEntity entity = new ReportTaskJpaEntity();
        entity.id = t.id();
        entity.projectId = t.projectId();
        entity.userId = t.userId();
        entity.status = t.status();
        entity.stage = t.stage();
        entity.tokenUsage = t.tokenUsage();
        entity.errorCode = t.errorCode();
        entity.errorMessage = t.errorMessage();
        entity.createdAt = t.createdAt();
        entity.updatedAt = t.updatedAt();
        entity.startedAt = t.startedAt();
        entity.finishedAt = t.finishedAt();
        return entity;
    }

    public ReportTask toDomain() {
        return ReportTask.reconstitute(id, userId, projectId, status, stage, tokenUsage,
                errorCode, errorMessage, createdAt, updatedAt, startedAt, finishedAt);
    }
}

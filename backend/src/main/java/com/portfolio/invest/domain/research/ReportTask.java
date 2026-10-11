package com.portfolio.invest.domain.research;

import java.time.Instant;

/**
 * 研报任务（MS-32 深度研究工作流，设计规格 §3.2）：不可变，变更操作返回新实例
 * （照 {@link ResearchProject} 先例）。四态状态机 PENDING→RUNNING→SUCCEEDED/FAILED，
 * 四阶段进度 COLLECTING→ANALYZING→DRAFTING→VALIDATING（阶段在 RUNNING 内任意推进/回退）。
 *
 * <p>时间全入参（D5 时钟纪律）：工厂与状态迁移方法的 {@code Instant now} 由调用方注入，零直调。
 */
public final class ReportTask {

    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED }
    public enum Stage { COLLECTING, ANALYZING, DRAFTING, VALIDATING }

    private final Long id;
    private final Long userId;
    private final Long projectId;
    private final Status status;
    private final Stage stage;
    private final long tokenUsage;
    private final String errorCode;
    private final String errorMessage;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant startedAt;
    private final Instant finishedAt;

    private ReportTask(Long id, Long userId, Long projectId, Status status, Stage stage,
                       long tokenUsage, String errorCode, String errorMessage,
                       Instant createdAt, Instant updatedAt, Instant startedAt, Instant finishedAt) {
        this.id = id;
        this.userId = userId;
        this.projectId = projectId;
        this.status = status;
        this.stage = stage;
        this.tokenUsage = tokenUsage;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    /** 新建任务：status=PENDING、stage=COLLECTING、tokenUsage=0，startedAt/finishedAt 空。 */
    public static ReportTask create(Long userId, Long projectId, Instant now) {
        if (userId == null) {
            throw new ResearchException(ResearchErrorCode.USER_REQUIRED, "归属用户不能为空");
        }
        if (projectId == null) {
            throw new ResearchException(ResearchErrorCode.PROJECT_REQUIRED, "归属项目不能为空");
        }
        return new ReportTask(null, userId, projectId, Status.PENDING, Stage.COLLECTING,
                0L, null, null, now, now, null, null);
    }

    /** 持久化还原（JPA 转换器用，不做校验）。 */
    public static ReportTask reconstitute(Long id, Long userId, Long projectId, Status status, Stage stage,
                                          long tokenUsage, String errorCode, String errorMessage,
                                          Instant createdAt, Instant updatedAt,
                                          Instant startedAt, Instant finishedAt) {
        return new ReportTask(id, userId, projectId, status, stage, tokenUsage,
                errorCode, errorMessage, createdAt, updatedAt, startedAt, finishedAt);
    }

    /** 启动：PENDING→RUNNING 落 startedAt；非 PENDING 抛 {@code TASK_INVALID_STATE}。 */
    public ReportTask markRunning(Instant now) {
        if (status != Status.PENDING) {
            throw new ResearchException(ResearchErrorCode.TASK_INVALID_STATE,
                    "仅 PENDING 任务可启动，当前态 " + status);
        }
        return new ReportTask(id, userId, projectId, Status.RUNNING, stage,
                tokenUsage, errorCode, errorMessage, createdAt, now, now, finishedAt);
    }

    /** 阶段推进（RUNNING 内任意跳转/回退，含回炉轮）；同阶段幂等返回原实例（照 changeStage 同值语义）。 */
    public ReportTask markStage(Stage next, Instant now) {
        if (next == null) {
            throw new ResearchException(ResearchErrorCode.STAGE_REQUIRED, "目标阶段不能为空");
        }
        if (status != Status.RUNNING) {
            throw new ResearchException(ResearchErrorCode.TASK_INVALID_STATE,
                    "仅 RUNNING 任务可推进阶段，当前态 " + status);
        }
        if (next == stage) {
            return this;
        }
        return new ReportTask(id, userId, projectId, status, next,
                tokenUsage, errorCode, errorMessage, createdAt, now, startedAt, finishedAt);
    }

    /** 成功：RUNNING→SUCCEEDED 落 finishedAt 与最终 tokenUsage；非 RUNNING 抛 {@code TASK_INVALID_STATE}。 */
    public ReportTask succeed(long tokenUsage, Instant now) {
        if (status != Status.RUNNING) {
            throw new ResearchException(ResearchErrorCode.TASK_INVALID_STATE,
                    "仅 RUNNING 任务可成功收尾，当前态 " + status);
        }
        return new ReportTask(id, userId, projectId, Status.SUCCEEDED, stage,
                tokenUsage, null, null, createdAt, now, startedAt, now);
    }

    /** 失败（任意态→FAILED，D3 失败重来）：落 errorCode/errorMessage/tokenUsage/finishedAt（FAILED 再失败覆盖诊断）。 */
    public ReportTask fail(String errorCode, String errorMessage, long tokenUsage, Instant now) {
        return new ReportTask(id, userId, projectId, Status.FAILED, stage,
                tokenUsage, errorCode, errorMessage, createdAt, now, startedAt, now);
    }

    public Long id() { return id; }
    public Long userId() { return userId; }
    public Long projectId() { return projectId; }
    public Status status() { return status; }
    public Stage stage() { return stage; }
    public long tokenUsage() { return tokenUsage; }
    public String errorCode() { return errorCode; }
    public String errorMessage() { return errorMessage; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
}

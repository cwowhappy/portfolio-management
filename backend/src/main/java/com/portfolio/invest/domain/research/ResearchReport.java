package com.portfolio.invest.domain.research;

import java.time.Instant;
import java.util.Optional;

/**
 * 研报（MS-32 深度研究工作流，设计规格 §3.10 确认状态机）：不可变，变更操作返回新实例。
 * 三态 PENDING_CONFIRM→CONFIRMED/REJECTED（照 {@link StrategyDoc} 两级状态机先例）：
 * confirm/reject 返回 {@link Optional}——当前态不允许（终态再操作）= empty，调用方判空转 409；
 * 并发争用由持久层乐观锁/条件更新兜底（恰一成功）。
 *
 * <p>时间全入参（D5 时钟纪律）：工厂与状态迁移方法的 {@code Instant now} 由调用方注入，零直调。
 */
public final class ResearchReport {

    public enum Status { PENDING_CONFIRM, CONFIRMED, REJECTED }

    private final Long id;
    private final Long taskId;
    private final Long projectId;
    private final Long userId;
    private final Status status;
    private final String title;
    private final String contentMd;
    private final String limitations;
    private final Instant confirmedAt;
    private final String rejectedReason;
    private final Instant createdAt;
    private final Instant updatedAt;

    private ResearchReport(Long id, Long taskId, Long projectId, Long userId, Status status,
                           String title, String contentMd, String limitations,
                           Instant confirmedAt, String rejectedReason,
                           Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.taskId = taskId;
        this.projectId = projectId;
        this.userId = userId;
        this.status = status;
        this.title = title;
        this.contentMd = contentMd;
        this.limitations = limitations;
        this.confirmedAt = confirmedAt;
        this.rejectedReason = rejectedReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 新建研报（任务成功落库）：status=PENDING_CONFIRM 待用户确认（D2 确认卡）；limitations 可空。 */
    public static ResearchReport create(Long taskId, Long projectId, Long userId, String title,
                                        String contentMd, String limitations, Instant now) {
        if (taskId == null) {
            throw new ResearchException(ResearchErrorCode.TASK_REQUIRED, "归属任务不能为空");
        }
        if (projectId == null) {
            throw new ResearchException(ResearchErrorCode.PROJECT_REQUIRED, "归属项目不能为空");
        }
        if (userId == null) {
            throw new ResearchException(ResearchErrorCode.USER_REQUIRED, "归属用户不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new ResearchException(ResearchErrorCode.TITLE_BLANK, "研报标题不能为空");
        }
        if (contentMd == null || contentMd.isBlank()) {
            throw new ResearchException(ResearchErrorCode.REPORT_CONTENT_REQUIRED, "研报正文不能为空");
        }
        return new ResearchReport(null, taskId, projectId, userId, Status.PENDING_CONFIRM,
                title, contentMd, limitations, null, null, now, now);
    }

    /** 持久化还原（JPA 转换器用，不做校验）。 */
    public static ResearchReport reconstitute(Long id, Long taskId, Long projectId, Long userId, Status status,
                                              String title, String contentMd, String limitations,
                                              Instant confirmedAt, String rejectedReason,
                                              Instant createdAt, Instant updatedAt) {
        return new ResearchReport(id, taskId, projectId, userId, status, title, contentMd,
                limitations, confirmedAt, rejectedReason, createdAt, updatedAt);
    }

    /** 确认定稿：PENDING_CONFIRM→CONFIRMED 落 confirmedAt；终态再操作返回 empty（调用方转 409）。 */
    public Optional<ResearchReport> confirm(Instant now) {
        if (status != Status.PENDING_CONFIRM) {
            return Optional.empty();
        }
        return Optional.of(new ResearchReport(id, taskId, projectId, userId, Status.CONFIRMED,
                title, contentMd, limitations, now, null, createdAt, now));
    }

    /** 拒绝（理由必填留痕）：PENDING_CONFIRM→REJECTED；终态再操作返回 empty。 */
    public Optional<ResearchReport> reject(String reason, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new ResearchException(ResearchErrorCode.REJECT_REASON_REQUIRED, "拒绝理由不能为空");
        }
        if (status != Status.PENDING_CONFIRM) {
            return Optional.empty();
        }
        return Optional.of(new ResearchReport(id, taskId, projectId, userId, Status.REJECTED,
                title, contentMd, limitations, null, reason, createdAt, now));
    }

    public Long id() { return id; }
    public Long taskId() { return taskId; }
    public Long projectId() { return projectId; }
    public Long userId() { return userId; }
    public Status status() { return status; }
    public String title() { return title; }
    public String contentMd() { return contentMd; }
    public String limitations() { return limitations; }
    public Instant confirmedAt() { return confirmedAt; }
    public String rejectedReason() { return rejectedReason; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}

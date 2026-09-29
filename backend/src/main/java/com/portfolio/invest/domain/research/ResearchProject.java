package com.portfolio.invest.domain.research;

import java.time.Instant;

/**
 * 研究项目聚合根（F05 立项 / S1 两态生命周期 / D4 灵活流转）：不可变，
 * 变更操作返回新实例（照 {@code JournalEntry} 先例）。阶段可任意跳转，
 * 完成度不落在本聚合——由 {@link StageCompletionService} 读时推导（S6）。
 */
public final class ResearchProject {

    private final Long id;
    private final Long userId;
    private final String stockCode;
    private final String stockName;
    private final String industryCode;
    private final String title;
    private final ResearchStage currentStage;
    private final ProjectStatus status;
    private final Long version;
    private final Instant createdAt;
    private final Instant updatedAt;

    private ResearchProject(Long id, Long userId, String stockCode, String stockName, String industryCode,
                            String title, ResearchStage currentStage, ProjectStatus status,
                            Long version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.industryCode = industryCode;
        this.title = title;
        this.currentStage = currentStage;
        this.status = status;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static ResearchProject create(Long userId, String stockCode, String stockName,
                                         String industryCode, String title, ResearchStage initialStage) {
        if (userId == null) {
            throw new ResearchException(ResearchErrorCode.USER_REQUIRED, "归属用户不能为空");
        }
        if (stockCode == null || stockCode.isBlank()) {
            throw new ResearchException(ResearchErrorCode.CODE_BLANK, "标的代码不能为空");
        }
        if (stockName == null || stockName.isBlank()) {
            throw new ResearchException(ResearchErrorCode.NAME_BLANK, "标的名称不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new ResearchException(ResearchErrorCode.TITLE_BLANK, "标题不能为空");
        }
        if (initialStage == null) {
            throw new ResearchException(ResearchErrorCode.STAGE_REQUIRED, "初始阶段不能为空");
        }
        Instant now = Instant.now();
        return new ResearchProject(null, userId, stockCode, stockName, industryCode, title,
                initialStage, ProjectStatus.ACTIVE, null, now, now);
    }

    public static ResearchProject reconstitute(Long id, Long userId, String stockCode, String stockName,
                                               String industryCode, String title, ResearchStage currentStage,
                                               ProjectStatus status, Long version,
                                               Instant createdAt, Instant updatedAt) {
        return new ResearchProject(id, userId, stockCode, stockName, industryCode, title,
                currentStage, status, version, createdAt, updatedAt);
    }

    /** 归档（S1）：幂等语义，ARCHIVED 再归档原样返回。 */
    public ResearchProject archive() {
        if (status == ProjectStatus.ARCHIVED) {
            return this;
        }
        return new ResearchProject(id, userId, stockCode, stockName, industryCode, title,
                currentStage, ProjectStatus.ARCHIVED, version, createdAt, Instant.now());
    }

    /** 切换当前阶段（D4 灵活流转：任意跳转/回退，不校验顺序）。 */
    public ResearchProject changeStage(ResearchStage stage) {
        if (stage == null) {
            throw new ResearchException(ResearchErrorCode.STAGE_REQUIRED, "目标阶段不能为空");
        }
        if (stage == currentStage) {
            return this;
        }
        return new ResearchProject(id, userId, stockCode, stockName, industryCode, title,
                stage, status, version, createdAt, Instant.now());
    }

    /** 改标题。 */
    public ResearchProject rename(String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new ResearchException(ResearchErrorCode.TITLE_BLANK, "标题不能为空");
        }
        if (newTitle.equals(title)) {
            return this;
        }
        return new ResearchProject(id, userId, stockCode, stockName, industryCode, newTitle,
                currentStage, status, version, createdAt, Instant.now());
    }

    public Long id() { return id; }
    public Long userId() { return userId; }
    public String stockCode() { return stockCode; }
    public String stockName() { return stockName; }
    public String industryCode() { return industryCode; }
    public String title() { return title; }
    public ResearchStage currentStage() { return currentStage; }
    public ProjectStatus status() { return status; }
    public Long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}

package com.portfolio.invest.domain.research;

import java.time.Instant;

/**
 * 证伪评审留痕（research_falsifier_review，M16-F15，NFR-2 append-only）：一次评审结论 =
 * 一条不可变记录，无 version/updated_at、不提供任何 update 方法（repository 亦不暴露
 * 评审更新用例——hit 表 review_id 回填是唯一例外，见 {@link FalsifierReviewRepository#attachReview}）。
 * 理由必填（空白或超 1000 字抛 {@code REVIEW_REASON_REQUIRED}，规格 F15「结论枚举 + 理由」）；
 * REVISE 结论不隐式改策略状态（Review Focus 3）。照 {@link CheckRecord} 先例：
 * 静态工厂构造、reconstitute 持久化还原。
 */
public final class FalsifierReview {

    /** DB research_falsifier_review.reason 为 VARCHAR(1000)。 */
    private static final int REASON_MAX_LENGTH = 1000;

    private final Long id;
    private final Long projectId;
    private final ReviewConclusion conclusion;
    private final String reason;
    private final Instant createdAt;

    private FalsifierReview(Long id, Long projectId, ReviewConclusion conclusion, String reason, Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.conclusion = conclusion;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    /** 新建评审留痕：结论必选四值之一；理由非空白且 ≤ 1000 字。 */
    public static FalsifierReview create(Long projectId, ReviewConclusion conclusion, String reason) {
        if (projectId == null) {
            throw new ResearchException(ResearchErrorCode.PROJECT_REQUIRED, "归属项目不能为空");
        }
        if (conclusion == null) {
            throw new ResearchException(ResearchErrorCode.CONCLUSION_REQUIRED, "评审结论不能为空");
        }
        if (reason == null || reason.isBlank()) {
            throw new ResearchException(ResearchErrorCode.REVIEW_REASON_REQUIRED, "评审理由不能为空");
        }
        if (reason.length() > REASON_MAX_LENGTH) {
            throw new ResearchException(ResearchErrorCode.REVIEW_REASON_REQUIRED, "评审理由长度不能超过1000字");
        }
        return new FalsifierReview(null, projectId, conclusion, reason, Instant.now());
    }

    /** 持久化还原（JPA 转换器用，不做校验）。 */
    public static FalsifierReview reconstitute(Long id, Long projectId, ReviewConclusion conclusion,
                                               String reason, Instant createdAt) {
        return new FalsifierReview(id, projectId, conclusion, reason, createdAt);
    }

    public Long id() { return id; }
    public Long projectId() { return projectId; }
    public ReviewConclusion conclusion() { return conclusion; }
    public String reason() { return reason; }
    public Instant createdAt() { return createdAt; }
}

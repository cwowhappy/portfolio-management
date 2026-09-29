package com.portfolio.invest.domain.research;

import java.time.Instant;

/**
 * 模板改进建议（research_feedback，M16-F16）：「建议内容/对应阶段/来源复盘」结构化沉淀，
 * 反哺 SOP v2 内容迭代——<b>只收集不生效</b>（v1 不做模板自动改写）。append-only 留痕
 * （照 {@link CheckRecord} 先例）：无 version/updated_at、不提供任何 update 方法；
 * reviewId 为来源复盘可空软引用（D11 同法，无 FK 不级联）。
 */
public final class ResearchFeedback {

    /** DB research_feedback.content 为 VARCHAR(1000)。 */
    private static final int CONTENT_MAX_LENGTH = 1000;

    private final Long id;
    private final Long projectId;
    private final Long reviewId;
    private final ResearchStage stage;
    private final String content;
    private final Instant createdAt;

    private ResearchFeedback(Long id, Long projectId, Long reviewId, ResearchStage stage,
                             String content, Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.reviewId = reviewId;
        this.stage = stage;
        this.content = content;
        this.createdAt = createdAt;
    }

    /** 新建建议：阶段必选（建议针对哪一档模板）、内容非空白且 ≤ 1000 字；来源复盘可空。 */
    public static ResearchFeedback create(Long projectId, Long reviewId, ResearchStage stage, String content) {
        if (projectId == null) {
            throw new ResearchException(ResearchErrorCode.PROJECT_REQUIRED, "归属项目不能为空");
        }
        if (stage == null) {
            throw new ResearchException(ResearchErrorCode.STAGE_REQUIRED, "建议针对阶段不能为空");
        }
        if (content == null || content.isBlank() || content.length() > CONTENT_MAX_LENGTH) {
            throw new ResearchException(ResearchErrorCode.FEEDBACK_CONTENT_REQUIRED,
                    "建议内容不能为空且长度不能超过" + CONTENT_MAX_LENGTH + "字");
        }
        return new ResearchFeedback(null, projectId, reviewId, stage, content, Instant.now());
    }

    /** 持久化还原（JPA 转换器用，不做校验）。 */
    public static ResearchFeedback reconstitute(Long id, Long projectId, Long reviewId,
                                                ResearchStage stage, String content, Instant createdAt) {
        return new ResearchFeedback(id, projectId, reviewId, stage, content, createdAt);
    }

    public Long id() { return id; }
    public Long projectId() { return projectId; }
    public Long reviewId() { return reviewId; }
    public ResearchStage stage() { return stage; }
    public String content() { return content; }
    public Instant createdAt() { return createdAt; }
}

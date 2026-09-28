package com.portfolio.invest.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 模板改进建议聚合（F16 只收集）：create 域校验 + reconstitute 全字段还原。 */
class ResearchFeedbackTest {

    @DisplayName("create 新建：stage/content 必填落位，reviewId 可空，id 待库分配")
    @Test
    void givenValidArgs_whenCreate_thenFeedbackWithOptionalReview() {
        ResearchFeedback withReview = ResearchFeedback.create(5L, 41L, ResearchStage.REVIEW, "月度模板建议增加仓位口径问题");
        assertThat(withReview.id()).isNull();
        assertThat(withReview.projectId()).isEqualTo(5L);
        assertThat(withReview.reviewId()).isEqualTo(41L);
        assertThat(withReview.stage()).isEqualTo(ResearchStage.REVIEW);
        assertThat(withReview.content()).isEqualTo("月度模板建议增加仓位口径问题");
        assertThat(withReview.createdAt()).isNotNull();

        ResearchFeedback withoutReview = ResearchFeedback.create(5L, null, ResearchStage.STRATEGY, "策略模板 checklist 太长");
        assertThat(withoutReview.reviewId()).isNull();
    }

    @DisplayName("create 缺项目抛 PROJECT_REQUIRED")
    @Test
    void givenNullProject_whenCreate_thenThrowProjectRequired() {
        assertThatThrownBy(() -> ResearchFeedback.create(null, null, ResearchStage.REVIEW, "建议"))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.PROJECT_REQUIRED));
    }

    @DisplayName("create 缺阶段抛 STAGE_REQUIRED")
    @Test
    void givenNullStage_whenCreate_thenThrowStageRequired() {
        assertThatThrownBy(() -> ResearchFeedback.create(5L, null, null, "建议"))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.STAGE_REQUIRED));
    }

    @DisplayName("create 内容空白/超 1000 字抛 FEEDBACK_CONTENT_REQUIRED")
    @Test
    void givenBlankOrOverlongContent_whenCreate_thenThrowContentRequired() {
        assertThatThrownBy(() -> ResearchFeedback.create(5L, null, ResearchStage.REVIEW, "  "))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.FEEDBACK_CONTENT_REQUIRED));
        assertThatThrownBy(() -> ResearchFeedback.create(5L, null, ResearchStage.REVIEW, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.FEEDBACK_CONTENT_REQUIRED));
        assertThatThrownBy(() -> ResearchFeedback.create(5L, null, ResearchStage.REVIEW, "建".repeat(1001)))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.FEEDBACK_CONTENT_REQUIRED));
        // 恰 1000 字边界放行
        assertThat(ResearchFeedback.create(5L, null, ResearchStage.REVIEW, "建".repeat(1000)).content())
                .hasSize(1000);
    }

    @DisplayName("reconstitute 全字段还原（不做校验）")
    @Test
    void givenPersistedRow_whenReconstitute_thenAllFieldsRestored() {
        Instant created = Instant.parse("2026-03-01T00:00:00Z");

        ResearchFeedback feedback = ResearchFeedback.reconstitute(11L, 5L, 9L,
                ResearchStage.POSITION, "建仓模板建议补资金费率项", created);

        assertThat(feedback.id()).isEqualTo(11L);
        assertThat(feedback.projectId()).isEqualTo(5L);
        assertThat(feedback.reviewId()).isEqualTo(9L);
        assertThat(feedback.stage()).isEqualTo(ResearchStage.POSITION);
        assertThat(feedback.content()).isEqualTo("建仓模板建议补资金费率项");
        assertThat(feedback.createdAt()).isEqualTo(created);
    }
}

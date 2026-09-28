package com.portfolio.invest.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FalsifierReview 聚合工厂校验（M16-F15 append-only 评审留痕，结论枚举 + 理由必填）。 */
class FalsifierReviewTest {

    private static final Instant NOW = Instant.parse("2026-09-28T08:00:00Z");

    @DisplayName("create：四结论留痕（结论 + 理由 + 时间戳），id 为 null 由落库生成")
    @Test
    void givenFourConclusions_whenCreate_thenReviewCarriesConclusionAndReason() {
        for (ReviewConclusion conclusion : ReviewConclusion.values()) {
            FalsifierReview review = FalsifierReview.create(5L, conclusion, "跌破下限，处置：" + conclusion.label());

            assertThat(review.id()).isNull();
            assertThat(review.projectId()).isEqualTo(5L);
            assertThat(review.conclusion()).isEqualTo(conclusion);
            assertThat(review.reason()).isEqualTo("跌破下限，处置：" + conclusion.label());
            assertThat(review.createdAt()).isNotNull();
        }
    }

    @DisplayName("create：projectId/conclusion 缺失 → PROJECT_REQUIRED / CONCLUSION_REQUIRED")
    @Test
    void givenMissingProjectOrConclusion_whenCreate_thenRejected() {
        assertThatThrownBy(() -> FalsifierReview.create(null, ReviewConclusion.EXIT, "跌破下限"))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.PROJECT_REQUIRED));
        assertThatThrownBy(() -> FalsifierReview.create(5L, null, "跌破下限"))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.CONCLUSION_REQUIRED));
    }

    @DisplayName("create：理由空白 → REVIEW_REASON_REQUIRED；超 1000 字 → REVIEW_REASON_REQUIRED（DB 列宽）")
    @Test
    void givenBlankOrOverlongReason_whenCreate_thenRejected() {
        assertThatThrownBy(() -> FalsifierReview.create(5L, ReviewConclusion.HOLD, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REVIEW_REASON_REQUIRED));
        assertThatThrownBy(() -> FalsifierReview.create(5L, ReviewConclusion.HOLD, "   "))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REVIEW_REASON_REQUIRED));
        assertThatThrownBy(() -> FalsifierReview.create(5L, ReviewConclusion.HOLD, "长".repeat(1001)))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REVIEW_REASON_REQUIRED));
        // 恰 1000 字放行（边界内）
        assertThat(FalsifierReview.create(5L, ReviewConclusion.HOLD, "长".repeat(1000)).reason()).hasSize(1000);
    }

    @DisplayName("reconstitute：持久化还原字段保真（append-only：聚合无任何 update 方法）")
    @Test
    void givenRow_whenReconstitute_thenFieldsPreserved() {
        FalsifierReview review = FalsifierReview.reconstitute(41L, 5L, ReviewConclusion.REDUCE,
                "跌破下限，先减半仓", NOW);

        assertThat(review.id()).isEqualTo(41L);
        assertThat(review.projectId()).isEqualTo(5L);
        assertThat(review.conclusion()).isEqualTo(ReviewConclusion.REDUCE);
        assertThat(review.reason()).isEqualTo("跌破下限，先减半仓");
        assertThat(review.createdAt()).isEqualTo(NOW);
    }

    @DisplayName("ReviewConclusion label：维持/减仓/退出/修订策略（journal 事件文案源，规格 F15 逐字）")
    @Test
    void givenConclusions_whenLabel_thenChineseLabels() {
        assertThat(ReviewConclusion.HOLD.label()).isEqualTo("维持");
        assertThat(ReviewConclusion.REDUCE.label()).isEqualTo("减仓");
        assertThat(ReviewConclusion.EXIT.label()).isEqualTo("退出");
        assertThat(ReviewConclusion.REVISE.label()).isEqualTo("修订策略");
    }
}

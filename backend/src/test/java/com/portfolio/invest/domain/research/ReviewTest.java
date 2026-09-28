package com.portfolio.invest.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 复盘聚合状态机：创建定格 / 作答覆盖 / 回流幂等（Focus 4/5 + 区间校验）。 */
class ReviewTest {

    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2026, 2, 28);

    private static Review created() {
        return Review.create(1L, ReviewTier.MONTHLY, START, END,
                "{\"periodReturn\":0.05}", List.of(3L, 5L));
    }

    // ---- create：创建即定格 ----

    @DisplayName("create 新建：PENDING 态、answers/overrides 空、快照与 trade_ids 定格")
    @Test
    void givenValidArgs_whenCreate_thenPendingReviewWithFrozenSnapshot() {
        Review review = created();

        assertThat(review.id()).isNull();
        assertThat(review.projectId()).isEqualTo(1L);
        assertThat(review.tier()).isEqualTo(ReviewTier.MONTHLY);
        assertThat(review.periodStart()).isEqualTo(START);
        assertThat(review.periodEnd()).isEqualTo(END);
        assertThat(review.snapshotJson()).isEqualTo("{\"periodReturn\":0.05}");
        assertThat(review.tradeIds()).containsExactly(3L, 5L);
        assertThat(review.answersJson()).isNull();
        assertThat(review.overridesJson()).isNull();
        assertThat(review.refluxState()).isEqualTo(RefluxState.PENDING);
        assertThat(review.wikiEntryId()).isNull();
        assertThat(review.version()).isNull();
        assertThat(review.createdAt()).isNotNull();
        assertThat(review.updatedAt()).isNotNull();
    }

    @DisplayName("create 缺项目抛 PROJECT_REQUIRED")
    @Test
    void givenNullProject_whenCreate_thenThrowProjectRequired() {
        assertThatThrownBy(() -> Review.create(null, ReviewTier.MONTHLY, START, END, "{}", List.of()))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.PROJECT_REQUIRED));
    }

    @DisplayName("create 缺档位抛 TIER_REQUIRED")
    @Test
    void givenNullTier_whenCreate_thenThrowTierRequired() {
        assertThatThrownBy(() -> Review.create(1L, null, START, END, "{}", List.of()))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.TIER_REQUIRED));
    }

    @DisplayName("create 区间倒置抛 REVIEW_PERIOD_INVALID")
    @Test
    void givenReversedPeriod_whenCreate_thenThrowPeriodInvalid() {
        assertThatThrownBy(() -> Review.create(1L, ReviewTier.QUARTERLY, END, START, "{}", List.of()))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REVIEW_PERIOD_INVALID));
    }

    @DisplayName("create 区间缺起止抛 REVIEW_PERIOD_INVALID")
    @Test
    void givenNullPeriod_whenCreate_thenThrowPeriodInvalid() {
        assertThatThrownBy(() -> Review.create(1L, ReviewTier.WEEKLY, null, END, "{}", List.of()))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REVIEW_PERIOD_INVALID));
    }

    @DisplayName("create 快照空白抛 SNAPSHOT_REQUIRED（创建即定格，不允许无快照复盘）")
    @Test
    void givenBlankSnapshot_whenCreate_thenThrowSnapshotRequired() {
        assertThatThrownBy(() -> Review.create(1L, ReviewTier.MONTHLY, START, END, "  ", List.of()))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.SNAPSHOT_REQUIRED));
    }

    @DisplayName("create trade_ids 重复乱序去重排序后落库（Focus 5）")
    @Test
    void givenDuplicatedUnsortedTradeIds_whenCreate_thenDedupSorted() {
        Review review = Review.create(1L, ReviewTier.MONTHLY, START, END, "{}",
                List.of(7L, 3L, 7L, 5L, 3L));

        assertThat(review.tradeIds()).containsExactly(3L, 5L, 7L);
    }

    @DisplayName("create trade_ids 缺省容忍为空列表")
    @Test
    void givenNullTradeIds_whenCreate_thenEmptyList() {
        Review review = Review.create(1L, ReviewTier.MONTHLY, START, END, "{}", null);

        assertThat(review.tradeIds()).isEmpty();
    }

    // ---- applyAnswers：作答与覆盖（变更返新，快照不动） ----

    @DisplayName("applyAnswers 变更返新：写 answers/overrides，原实例不变、快照保持定格")
    @Test
    void givenAnswers_whenApplyAnswers_thenNewInstanceAndSnapshotUntouched() {
        Review origin = created();

        Review answered = origin.applyAnswers("{\"q1\":\"追高\"}", "{\"periodReturn\":\"0.06\"}");

        assertThat(answered).isNotSameAs(origin);
        assertThat(answered.answersJson()).isEqualTo("{\"q1\":\"追高\"}");
        assertThat(answered.overridesJson()).isEqualTo("{\"periodReturn\":\"0.06\"}");
        assertThat(answered.snapshotJson()).isEqualTo("{\"periodReturn\":0.05}");
        assertThat(answered.tradeIds()).containsExactly(3L, 5L);
        assertThat(answered.refluxState()).isEqualTo(RefluxState.PENDING);
        assertThat(answered.createdAt()).isEqualTo(origin.createdAt());
        // 不可变：原实例未被污染
        assertThat(origin.answersJson()).isNull();
        assertThat(origin.overridesJson()).isNull();
    }

    @DisplayName("applyAnswers 作答空白抛 ANSWERS_REQUIRED")
    @Test
    void givenBlankAnswers_whenApplyAnswers_thenThrowAnswersRequired() {
        Review review = created();

        assertThatThrownBy(() -> review.applyAnswers(" ", null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.ANSWERS_REQUIRED));
    }

    // ---- correct：手动修正（P4-T3 PUT 路径，快照/回流状态不动） ----

    @DisplayName("correct 变更返新：answers/overrides/narrative 整替 + trade_ids 去重排序（Focus 5 手动路径共用收敛点），快照保持定格")
    @Test
    void givenCorrections_whenCorrect_thenAppliedWithFrozenSnapshotAndNormalizedTradeIds() {
        Review origin = created();

        Review corrected = origin.correct("{\"q1\":\"追高\"}", "{\"periodReturn\":\"0.06\"}",
                "事后看止损执行晚了", List.of(7L, 3L, 7L, 5L, 3L));

        assertThat(corrected).isNotSameAs(origin);
        assertThat(corrected.answersJson()).isEqualTo("{\"q1\":\"追高\"}");
        assertThat(corrected.overridesJson()).isEqualTo("{\"periodReturn\":\"0.06\"}");
        assertThat(corrected.narrative()).isEqualTo("事后看止损执行晚了");
        assertThat(corrected.tradeIds()).containsExactly(3L, 5L, 7L); // 去重升序（Focus 5）
        assertThat(corrected.snapshotJson()).isEqualTo("{\"periodReturn\":0.05}"); // 快照不可改
        assertThat(corrected.refluxState()).isEqualTo(RefluxState.PENDING); // 回流状态不动
        assertThat(corrected.createdAt()).isEqualTo(origin.createdAt());
        // 不可变：原实例未被污染
        assertThat(origin.answersJson()).isNull();
        assertThat(origin.tradeIds()).containsExactly(3L, 5L);
    }

    @DisplayName("correct trade_ids 缺省容忍为空列表（清空圈选）")
    @Test
    void givenNullTradeIds_whenCorrect_thenEmptyList() {
        Review corrected = created().correct("{\"q1\":\"追高\"}", null, null, null);

        assertThat(corrected.tradeIds()).isEmpty();
        assertThat(corrected.overridesJson()).isNull();
        assertThat(corrected.narrative()).isNull();
    }

    @DisplayName("correct 作答空白抛 ANSWERS_REQUIRED")
    @Test
    void givenBlankAnswers_whenCorrect_thenThrowAnswersRequired() {
        assertThatThrownBy(() -> created().correct("  ", null, null, List.of()))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.ANSWERS_REQUIRED));
    }

    @DisplayName("correct REFLOWN 态仍可修正叙述（宽容，快照/回流状态/条目不动）")
    @Test
    void givenRefowned_whenCorrect_thenNarrativeReplacedButRefluxUntouched() {
        Review refowned = created().refluxConfirm(100L);

        Review corrected = refowned.correct("{\"q1\":\"ok\"}", null, "补充叙述", null);

        assertThat(corrected.refluxState()).isEqualTo(RefluxState.REFLOWN);
        assertThat(corrected.wikiEntryId()).isEqualTo(100L);
        assertThat(corrected.narrative()).isEqualTo("补充叙述");
    }

    // ---- refluxConfirm：回流状态机（Focus 4 幂等） ----

    @DisplayName("refluxConfirm PENDING → REFLOWN 并记 wikiEntryId")
    @Test
    void givenPending_whenRefluxConfirm_thenRefownWithEntryId() {
        Review review = created();

        Review refowned = review.refluxConfirm(100L);

        assertThat(refowned.refluxState()).isEqualTo(RefluxState.REFLOWN);
        assertThat(refowned.wikiEntryId()).isEqualTo(100L);
        assertThat(refowned.snapshotJson()).isEqualTo("{\"periodReturn\":0.05}");
        assertThat(refowned.createdAt()).isEqualTo(review.createdAt());
        assertThat(refowned.updatedAt()).isNotNull();
    }

    @DisplayName("refluxConfirm 二次调用幂等返回既有 entryId（传入不同 id 也不覆盖，Focus 4）")
    @Test
    void givenRefowned_whenRefluxConfirmAgain_thenIdempotentKeepsExistingEntry() {
        Review refowned = created().refluxConfirm(100L);

        Review again = refowned.refluxConfirm(999L);

        assertThat(again).isSameAs(refowned);
        assertThat(again.refluxState()).isEqualTo(RefluxState.REFLOWN);
        assertThat(again.wikiEntryId()).isEqualTo(100L);
    }

    @DisplayName("refluxConfirm 缺回流条目抛 WIKI_ENTRY_REQUIRED")
    @Test
    void givenNullWikiEntry_whenRefluxConfirm_thenThrowWikiEntryRequired() {
        Review review = created();

        assertThatThrownBy(() -> review.refluxConfirm(null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.WIKI_ENTRY_REQUIRED));
    }

    // ---- reconstitute：持久化还原 ----

    @DisplayName("reconstitute 全字段还原（含 CONFIRMED 中间态与 narrative，不做校验）")
    @Test
    void givenPersistedRow_whenReconstitute_thenAllFieldsRestored() {
        Instant created = Instant.parse("2026-03-01T00:00:00Z");
        Instant updated = Instant.parse("2026-03-02T00:00:00Z");

        Review review = Review.reconstitute(9L, 1L, ReviewTier.QUARTERLY,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31),
                "{\"q1\":\"ok\"}", "事后看止损执行晚了", "{\"snapshot\":1}", "{\"o\":1}",
                List.of(3L, 5L), RefluxState.CONFIRMED, 77L, 2L, created, updated);

        assertThat(review.id()).isEqualTo(9L);
        assertThat(review.projectId()).isEqualTo(1L);
        assertThat(review.tier()).isEqualTo(ReviewTier.QUARTERLY);
        assertThat(review.periodStart()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(review.periodEnd()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(review.answersJson()).isEqualTo("{\"q1\":\"ok\"}");
        assertThat(review.narrative()).isEqualTo("事后看止损执行晚了");
        assertThat(review.snapshotJson()).isEqualTo("{\"snapshot\":1}");
        assertThat(review.overridesJson()).isEqualTo("{\"o\":1}");
        assertThat(review.tradeIds()).containsExactly(3L, 5L);
        assertThat(review.refluxState()).isEqualTo(RefluxState.CONFIRMED);
        assertThat(review.wikiEntryId()).isEqualTo(77L);
        assertThat(review.version()).isEqualTo(2L);
        assertThat(review.createdAt()).isEqualTo(created);
        assertThat(review.updatedAt()).isEqualTo(updated);
    }

    @DisplayName("CONFIRMED 态 refluxConfirm 可完成回流（预留两步确认）")
    @Test
    void givenConfirmed_whenRefluxConfirm_thenRefown() {
        Review confirmed = Review.reconstitute(9L, 1L, ReviewTier.WEEKLY, START, END,
                null, null, "{}", null, List.of(), RefluxState.CONFIRMED, null, 0L,
                Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z"));

        Review refowned = confirmed.refluxConfirm(55L);

        assertThat(refowned.refluxState()).isEqualTo(RefluxState.REFLOWN);
        assertThat(refowned.wikiEntryId()).isEqualTo(55L);
    }
}

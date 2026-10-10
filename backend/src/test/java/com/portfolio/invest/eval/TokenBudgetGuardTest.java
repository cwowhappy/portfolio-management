package com.portfolio.invest.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * token 预算护栏单元守护（MS-30 终审 I-1 执行端接线）：每题完成后累计题级
 * {@code tokenUsage.totalTokens}（SSE token_usage cumulative 末快照即该题整跑累计，
 * 题间求和 = 当次运行总消耗），超过 {@code invest.eval.token-budget} 即中止剩余题
 * ——占位口径同 real 轨预留跳过（SKIPPED + skipReason + 零轮次）；runMeta 侧由
 * EvalRunner 标 PARTIAL + tokenBudgetExceeded（收割不任 baseline 候选）。
 */
class TokenBudgetGuardTest {

    // ———— 超限判定 ————

    @Test
    @DisplayName("给定累计未触达预算，when逐题累计，then不超限（直通，剩余题照常跑）")
    void givenUsageUnderBudget_whenAccumulated_thenNotExceeded() {
        TokenBudgetGuard guard = new TokenBudgetGuard(1_000_000);

        guard.accumulate(completed(600_000));
        guard.accumulate(completed(399_999));

        assertThat(guard.exceeded()).isFalse();
    }

    @Test
    @DisplayName("给定累计恰好等于预算，when累计，then不超限（超过取严格大于口径）")
    void givenUsageEqualsBudget_whenAccumulated_thenNotExceeded() {
        TokenBudgetGuard guard = new TokenBudgetGuard(1_000_000);

        guard.accumulate(completed(600_000));
        guard.accumulate(completed(400_000));

        assertThat(guard.exceeded()).isFalse();
    }

    @Test
    @DisplayName("给定累计越过预算，when累计，then超限且中止理由携累计/预算数字")
    void givenUsageOverBudget_whenAccumulated_thenExceededWithReason() {
        TokenBudgetGuard guard = new TokenBudgetGuard(1_000_000);

        guard.accumulate(completed(600_000));
        guard.accumulate(completed(500_001));

        assertThat(guard.exceeded()).isTrue();
        assertThat(guard.skipReason())
                .contains("token 预算超限")
                .contains("1,100,001")
                .contains("1,000,000")
                .contains("invest.eval.token-budget");
    }

    @Test
    @DisplayName("给定 tokenUsage 缺席的题（ERROR 路径），when累计，then安全跳过不计入也不致错")
    void givenOutcomeWithoutUsage_whenAccumulated_thenIgnoredSafely() {
        TokenBudgetGuard guard = new TokenBudgetGuard(1_000);

        guard.accumulate(new QuestionOutcome(question("q.err"), QuestionOutcome.Status.ERROR,
                null, "框架异常", null, null, 0, List.of(), List.of(), null, null, null, null));
        guard.accumulate(new QuestionOutcome(question("q.nosnap"), QuestionOutcome.Status.PASS,
                null, null, "eval_user", "eval-thread", 1_000L, List.of(), List.of(), null,
                new AguiEventExtractor.TokenUsage(null, null, null, 0), null, "答案")); // 有 tokenUsage 无 totalTokens 快照同样跳过

        assertThat(guard.exceeded()).isFalse();
        assertThat(guard.consumedTotalTokens()).isZero();
    }

    // ———— 剩余题中止占位 ————

    @Test
    @DisplayName("给定超限后剩余题，when占位，thenSKIPPED+skipReason+零轮次（同 real 轨预留跳过口径）")
    void givenExceeded_whenSkippedOutcome_thenSameShapeAsRealTrackSkip() {
        TokenBudgetGuard guard = new TokenBudgetGuard(1_000);
        guard.accumulate(completed(2_000));

        QuestionOutcome skipped = guard.skippedOutcome(question("q.rest"));

        assertThat(skipped.status()).isEqualTo(QuestionOutcome.Status.SKIPPED);
        assertThat(skipped.skipReason()).isEqualTo(guard.skipReason());
        assertThat(skipped.error()).isNull();
        assertThat(skipped.username()).isNull();
        assertThat(skipped.threadId()).isNull();
        assertThat(skipped.durationMs()).isZero();
        assertThat(skipped.turns()).isEmpty();
        assertThat(skipped.dimensions()).isEmpty();
        assertThat(skipped.judge()).isNull();
        assertThat(skipped.tokenUsage()).isNull();
        assertThat(skipped.trustStats()).isNull();
        assertThat(skipped.answerText()).isNull();
        assertThat(skipped.eventCount()).isZero();
    }

    // ———— 构造校验 ————

    @Test
    @DisplayName("给定非正预算，when构造，thenfail-fast（防误配 0/负值静默全跳）")
    void givenNonPositiveBudget_whenConstructed_thenFailFast() {
        assertThatThrownBy(() -> new TokenBudgetGuard(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token 预算必须为正数");
        assertThatThrownBy(() -> new TokenBudgetGuard(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ———— 夹具 ————

    /** 正常完赛题（tokenUsage 携 cumulative 末快照）。 */
    private static QuestionOutcome completed(long totalTokens) {
        return new QuestionOutcome(question("q.done"), QuestionOutcome.Status.PASS, null, null,
                "eval_user", "eval-thread", 1_000L, List.of(), List.of(), null,
                new AguiEventExtractor.TokenUsage(totalTokens, totalTokens / 4, totalTokens, 1),
                null, "答案正文");
    }

    private static EvalQuestion question(String id) {
        return new EvalQuestion(id, "MARKET_FACT", "stub", List.of("测试问题"),
                new EvalQuestion.Expect(null, null, null, null, null, null, null, null, null,
                        new EvalQuestion.HallucinationGuard(null), null),
                "rubric-answer-quality", null);
    }
}

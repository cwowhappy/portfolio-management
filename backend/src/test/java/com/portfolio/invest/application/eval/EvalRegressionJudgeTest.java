package com.portfolio.invest.application.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.application.eval.JudgeVerdict.Status;
import com.portfolio.invest.application.eval.RunResult.QuestionOutcomeLite;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 相对回归判定纯函数全分支守护（MS-30 B4，设计规格 §3.1）：降幅/翻转/三新类分类三阈值
 * 边界三分（低于不告警、恰等告警、超出告警——降幅恰等断言防 double 直减误差翻案）、
 * ERROR 计入分母不放宽、NO_BASELINE / INCOMPARABLE（+绝对完成率 &lt;80% 降级）、
 * 恢复判定（上一跑 DEGRADED 且本跑全未命中）。零 mock 零 IO，纯函数密度优先。
 */
class EvalRegressionJudgeTest {

    private static final String BANK = "bank-hash-v1";
    private static final Map<String, String> RUBRIC = Map.of("rubric.market_fact", "1");

    private final EvalRegressionJudge judge = new EvalRegressionJudge(10, 3, 15);

    // ———— 工厂：可比指纹（题库 hash + rubric 版本）缺省一致，明细/分类缺省空 ————

    private static RunResult result(int pass, int fail, int error) {
        return result(1.0, pass, fail, error, Map.of(), BANK, RUBRIC, List.of(), false);
    }

    private static RunResult result(double completeness, int pass, int fail, int error,
                                    Map<String, int[]> byCategory, String bankHash,
                                    Map<String, String> rubricVersions,
                                    List<QuestionOutcomeLite> outcomes, boolean prevRunDegraded) {
        return new RunResult(completeness, pass, fail, error, byCategory,
                bankHash, rubricVersions, outcomes, prevRunDegraded);
    }

    /** 题目级明细工厂：全 pass，或指定前缀 id 翻转为 FAIL。 */
    private static List<QuestionOutcomeLite> outcomes(int total, List<String> failedIds) {
        return java.util.stream.IntStream.rangeClosed(1, total)
                .mapToObj(i -> new QuestionOutcomeLite("q-" + i, !failedIds.contains("q-" + i)))
                .toList();
    }

    // ———— 总通过率降幅边界三分（Review Focus #1）———

    @Test
    @DisplayName("给定基线全过，when 本跑降 9pp，then 不告警")
    void givenCurrentDrop9pp_whenJudge_thenNone() {
        var base = result(100, 0, 0);

        var verdict = judge.judge(result(91, 9, 0), Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.NONE);
    }

    @Test
    @DisplayName("给定基线全过，when 本跑恰降 10pp（阈值含等于），then 告警（double 直减误差不得翻案）")
    void givenCurrentDropExactly10pp_whenJudge_thenDegraded() {
        var base = result(100, 0, 0);

        var verdict = judge.judge(result(90, 10, 0), Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.DEGRADED);
    }

    @Test
    @DisplayName("给定基线全过，when 本跑降 11pp，then 告警")
    void givenCurrentDrop11pp_whenJudge_thenDegraded() {
        var base = result(100, 0, 0);

        var verdict = judge.judge(result(89, 11, 0), Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.DEGRADED);
    }

    @Test
    @DisplayName("给定 101 题基线 pass 100，when 本跑 pass 90（降幅 9.90pp），then 不告警")
    void given101Bank_whenDropBelow10pp_thenNone() {
        var base = result(100, 1, 0);

        var verdict = judge.judge(result(90, 11, 0), Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.NONE);
    }

    @Test
    @DisplayName("给定 101 题基线 pass 100，when 本跑 pass 89（降幅 10.89pp），then 告警")
    void given101Bank_whenDropAbove10pp_thenDegraded() {
        var base = result(100, 1, 0);

        var verdict = judge.judge(result(89, 12, 0), Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.DEGRADED);
    }

    // ———— ERROR 口径（需求决策 #10：计入分母，不因 error>0 放宽）———

    @Test
    @DisplayName("给定基线全过、本跑仅 ERROR 增多（80/90），when 判定，then ERROR 计入分母告警")
    void givenErrorOnlyRise_whenJudge_thenErrorCountsInDenominator() {
        var base = result(90, 0, 0);

        var verdict = judge.judge(result(80, 0, 10), Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.DEGRADED);
    }

    // ———— PASS→FAIL 翻转（同题 id 对齐，阈值 3）———

    @Test
    @DisplayName("给定同题对齐翻转 2 题，when 判定，then 翻转未达阈值不告警")
    void givenFlip2_whenJudge_thenNone() {
        var base = result(1.0, 100, 0, 0, Map.of(), BANK, RUBRIC, outcomes(5, List.of()), false);
        var current = result(1.0, 98, 2, 0, Map.of(), BANK, RUBRIC,
                outcomes(5, List.of("q-4", "q-5")), false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.NONE);
    }

    @Test
    @DisplayName("给定同题对齐翻转 3 题（恰达阈值），when 判定，then 告警且理由含题 id")
    void givenFlip3_whenJudge_thenDegradedWithFlipReason() {
        var base = result(1.0, 100, 0, 0, Map.of(), BANK, RUBRIC, outcomes(5, List.of()), false);
        var current = result(1.0, 97, 3, 0, Map.of(), BANK, RUBRIC,
                outcomes(5, List.of("q-3", "q-4", "q-5")), false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.DEGRADED);
        assertThat(verdict.reasons()).anyMatch(r -> r.contains("翻转") && r.contains("q-3"));
    }

    @Test
    @DisplayName("给定基线缺题目级明细，when 本跑聚合降幅未超阈，then 跳过翻转判定不误报")
    void givenBaselineOutcomeDetailMissing_whenJudge_thenFlipJudgmentSkipped() {
        var base = result(1.0, 100, 0, 0, Map.of(), BANK, RUBRIC, List.of(), false);
        var current = result(1.0, 97, 3, 0, Map.of(), BANK, RUBRIC,
                outcomes(5, List.of("q-3", "q-4", "q-5")), false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.NONE);
    }

    // ———— 无 baseline ————

    @Test
    @DisplayName("给定 baseline 空（首跑/基准变更后），when 判定，then NO_BASELINE 且留理由")
    void givenNoBaseline_whenJudge_thenNoBaseline() {
        var verdict = judge.judge(result(50, 50, 0), Optional.empty());

        assertThat(verdict.status()).isEqualTo(Status.NO_BASELINE);
        assertThat(verdict.reasons()).isNotEmpty();
    }

    // ———— 不可比（题库 hash / rubric 版本指纹不一致）———

    @Test
    @DisplayName("给定题库 hash 不同且完成率达标，when 判定，then INCOMPARABLE 且标 BASELINE_INCOMPARABLE")
    void givenDifferentBankHash_whenCompletenessOk_thenIncomparable() {
        var base = result(100, 0, 0);
        var current = result(1.0, 100, 0, 0, Map.of(), "bank-hash-v2", RUBRIC, List.of(), false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.INCOMPARABLE);
        assertThat(verdict.reasons()).anyMatch(r -> r.contains("BASELINE_INCOMPARABLE"));
    }

    @Test
    @DisplayName("给定题库同源但 rubric 版本不同，when 判定，then INCOMPARABLE")
    void givenDifferentRubricVersion_whenJudge_thenIncomparable() {
        var base = result(100, 0, 0);
        var current = result(1.0, 100, 0, 0, Map.of(), BANK,
                Map.of("rubric.market_fact", "2"), List.of(), false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.INCOMPARABLE);
    }

    @Test
    @DisplayName("给定不可比且完成率恰 80%，when 判定，then 不降级（&lt;80% 才告警）")
    void givenIncomparable_whenCompletenessExactly80_thenNotDegraded() {
        var base = result(100, 0, 0);
        var current = result(0.8, 100, 0, 0, Map.of(), "bank-hash-v2", RUBRIC, List.of(), false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.INCOMPARABLE);
    }

    @Test
    @DisplayName("给定不可比且绝对完成率低于 80%，when 判定，then 降级告警并标不可比理由")
    void givenIncomparable_whenCompletenessBelow80_thenDegraded() {
        var base = result(100, 0, 0);
        var current = result(0.55, 55, 0, 0, Map.of(), "bank-hash-v2", RUBRIC, List.of(), false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.DEGRADED);
        assertThat(verdict.reasons()).anyMatch(r -> r.contains("BASELINE_INCOMPARABLE"));
        assertThat(verdict.reasons()).anyMatch(r -> r.contains("完成率"));
    }

    // ———— 三新类分类降幅（阈值 15，只遍历 MARKET_FACT/METRIC_CALC/HALLUCINATION_INDUCTION）———

    @Test
    @DisplayName("给定 METRIC_CALC 由 100%（20/20）降至 85%（17/20）恰降 15pp，when 判定，then 告警")
    void givenNewCategoryDropExactly15pp_whenJudge_thenDegraded() {
        var baseWithCategory = result(1.0, 100, 0, 0,
                Map.of("METRIC_CALC", new int[]{20, 0, 0}), BANK, RUBRIC, List.of(), false);
        var current = result(100, 0, 0, 0,
                Map.of("METRIC_CALC", new int[]{17, 3, 0}), BANK, RUBRIC, List.of(), false);

        var verdict = judge.judge(current, Optional.of(baseWithCategory));

        assertThat(verdict.status()).isEqualTo(Status.DEGRADED);
        assertThat(verdict.reasons()).anyMatch(r -> r.contains("METRIC_CALC"));
    }

    @Test
    @DisplayName("给定 METRIC_CALC 降 10pp（未达分类阈值），when 判定，then 不告警")
    void givenNewCategoryDrop10pp_whenJudge_thenNone() {
        var base = result(1.0, 100, 0, 0,
                Map.of("METRIC_CALC", new int[]{19, 1, 0}), BANK, RUBRIC, List.of(), false);
        var current = result(100, 0, 0, 0,
                Map.of("METRIC_CALC", new int[]{17, 3, 0}), BANK, RUBRIC, List.of(), false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.NONE);
    }

    @Test
    @DisplayName("给定非三新类（boundary）大幅下滑，when 判定，then 不参与分类判定")
    void givenNonNewCategoryDrop_whenJudge_thenIgnored() {
        var base = result(1.0, 100, 0, 0,
                Map.of("boundary", new int[]{10, 0, 0}), BANK, RUBRIC, List.of(), false);
        var current = result(100, 0, 0, 0,
                Map.of("boundary", new int[]{5, 5, 0}), BANK, RUBRIC, List.of(), false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.NONE);
    }

    @Test
    @DisplayName("给定分类仅单侧存在（baseline 缺该类），when 判定，then 跳过不误报")
    void givenCategoryMissingOnBaselineSide_whenJudge_thenSkipped() {
        var base = result(100, 0, 0);
        var current = result(100, 0, 0, 0,
                Map.of("METRIC_CALC", new int[]{1, 9, 0}), BANK, RUBRIC, List.of(), false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.NONE);
    }

    // ———— 恢复判定（上一跑 DEGRADED 且本跑全未命中）———

    @Test
    @DisplayName("给定上一跑 DEGRADED 且本跑全未命中，when 判定，then RECOVERED")
    void givenPrevRunDegraded_whenAllClear_thenRecovered() {
        var base = result(100, 0, 0);
        var current = result(1.0, 100, 0, 0, Map.of(), BANK, RUBRIC, List.of(), true);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.RECOVERED);
    }

    @Test
    @DisplayName("给定上一跑未告警且本跑全未命中，when 判定，then NONE（恢复不重复申报）")
    void givenPrevRunHealthy_whenAllClear_thenNone() {
        var base = result(100, 0, 0);
        var current = result(1.0, 100, 0, 0, Map.of(), BANK, RUBRIC, List.of(), false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.NONE);
    }

    @Test
    @DisplayName("给定上一跑 DEGRADED 且本跑仍命中降幅，when 判定，then 持续 DEGRADED（优先于恢复）")
    void givenPrevRunDegraded_whenStillHit_thenDegraded() {
        var base = result(100, 0, 0);
        var current = result(1.0, 80, 20, 0, Map.of(), BANK, RUBRIC, List.of(), true);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.DEGRADED);
    }

    @Test
    @DisplayName("给定上一跑 DEGRADED 但本跑换题库不可比，when 判定，then 维持 INCOMPARABLE（恢复只在可比分支判定）")
    void givenPrevRunDegraded_whenIncomparable_thenIncomparable() {
        var base = result(100, 0, 0);
        var current = result(0.9, 90, 0, 0, Map.of(), "bank-hash-v2", RUBRIC, List.of(), true);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.INCOMPARABLE);
    }

    @Test
    @DisplayName("给定上一跑 DEGRADED 但 baseline 空（基准变更后），when 判定，then NO_BASELINE 优先")
    void givenPrevRunDegraded_whenNoBaseline_thenNoBaseline() {
        var verdict = judge.judge(
                result(1.0, 100, 0, 0, Map.of(), BANK, RUBRIC, List.of(), true), Optional.empty());

        assertThat(verdict.status()).isEqualTo(Status.NO_BASELINE);
    }

    // ———— 防御分支（理由行收敛 / 分类形态 / null 归一）———

    @Test
    @DisplayName("给定同题翻转 11 题，when 判定，then 理由行收敛为「等共 11 题」防膨胀")
    void givenFlip11_whenJudge_thenReasonListCappedToSample() {
        var base = result(1.0, 100, 0, 0, Map.of(), BANK, RUBRIC, outcomes(11, List.of()), false);
        var failedIds = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(i -> "q-" + i).toList();
        var current = result(1.0, 89, 11, 0, Map.of(), BANK, RUBRIC,
                outcomes(11, failedIds), false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.DEGRADED);
        assertThat(verdict.reasons()).anyMatch(r -> r.contains("翻转 11 题") && r.endsWith("等共 11 题"));
    }

    @Test
    @DisplayName("给定分类行全零（无分母），when 判定，then 跳过该类不误报")
    void givenCategoryRowAllZero_whenJudge_thenSkipped() {
        var base = result(1.0, 100, 0, 0,
                Map.of("METRIC_CALC", new int[]{0, 0, 0}), BANK, RUBRIC, List.of(), false);
        var current = result(100, 0, 0, 0,
                Map.of("METRIC_CALC", new int[]{0, 0, 0}), BANK, RUBRIC, List.of(), false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.NONE);
    }

    @Test
    @DisplayName("给定分类行形态非法（长度不足 3），when 判定，then 跳过该类不误报")
    void givenMalformedCategoryRow_whenJudge_thenSkipped() {
        var base = result(1.0, 100, 0, 0,
                Map.of("METRIC_CALC", new int[]{20, 0}), BANK, RUBRIC, List.of(), false);
        var current = result(100, 0, 0, 0,
                Map.of("METRIC_CALC", new int[]{1, 0}), BANK, RUBRIC, List.of(), false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.NONE);
    }

    @Test
    @DisplayName("给定分类仅本跑侧存在（baseline 有、本跑缺），when 判定，then 跳过不误报")
    void givenCategoryMissingOnCurrentSide_whenJudge_thenSkipped() {
        var base = result(1.0, 100, 0, 0,
                Map.of("METRIC_CALC", new int[]{20, 0, 0}), BANK, RUBRIC, List.of(), false);
        var current = result(100, 0, 0, 0, Map.of(), BANK, RUBRIC, List.of(), false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.NONE);
    }

    @Test
    @DisplayName("给定双侧总题数全零（空跑无分母），when 判定，then 不判降幅不误报")
    void givenBothSidesZeroTotals_whenJudge_thenNoDropFalseAlarm() {
        var verdict = judge.judge(result(0, 0, 0), Optional.of(result(0, 0, 0)));

        assertThat(verdict.status()).isEqualTo(Status.NONE);
    }

    @Test
    @DisplayName("给定仅本跑总题数为零（单侧无分母），when 判定，then 不判降幅不误报")
    void givenCurrentZeroTotals_whenJudge_thenNoDropFalseAlarm() {
        var verdict = judge.judge(result(0, 0, 0), Optional.of(result(100, 0, 0)));

        assertThat(verdict.status()).isEqualTo(Status.NONE);
    }

    @Test
    @DisplayName("给定本跑新增题 FAIL（baseline 无同 id），when 判定，then 不计翻转（只对齐同题）")
    void givenFlipIdAbsentInBaseline_whenJudge_thenNotCounted() {
        var base = result(1.0, 100, 0, 0, Map.of(), BANK, RUBRIC,
                List.of(new QuestionOutcomeLite("q-1", true)), false);
        var current = result(1.0, 99, 1, 0, Map.of(), BANK, RUBRIC,
                List.of(new QuestionOutcomeLite("q-1", true), new QuestionOutcomeLite("q-new", false)),
                false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.NONE);
    }

    @Test
    @DisplayName("给定本跑缺题目级明细，when 判定，then 跳过翻转判定不误报（双侧齐备才对齐）")
    void givenCurrentOutcomeDetailMissing_whenJudge_thenFlipJudgmentSkipped() {
        var base = result(1.0, 100, 0, 0, Map.of(), BANK, RUBRIC, outcomes(5, List.of()), false);
        var current = result(99, 1, 0);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.NONE);
    }

    @Test
    @DisplayName("给定同题 id 在本跑明细重复出现，when 判定，then 翻转只计一次")
    void givenDuplicateFlipId_whenJudge_thenCountedOnce() {
        var base = result(1.0, 100, 0, 0, Map.of(), BANK, RUBRIC, outcomes(5, List.of()), false);
        var current = result(1.0, 98, 2, 0, Map.of(), BANK, RUBRIC,
                List.of(new QuestionOutcomeLite("q-1", false), new QuestionOutcomeLite("q-1", false),
                        new QuestionOutcomeLite("q-2", false), new QuestionOutcomeLite("q-2", false)),
                false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.NONE);
    }

    @Test
    @DisplayName("给定题库 hash 为 null（旧档缺 runMeta），when 判定，then 按不可比处理")
    void givenNullBankHash_whenJudge_thenIncomparable() {
        var base = result(100, 0, 0);
        var current = result(1.0, 100, 0, 0, Map.of(), null, RUBRIC, List.of(), false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.INCOMPARABLE);
    }

    @Test
    @DisplayName("给定 baseline 题库 hash 为 null（旧档基准），when 判定，then 按不可比处理")
    void givenNullBaselineBankHash_whenJudge_thenIncomparable() {
        var base = result(1.0, 100, 0, 0, Map.of(), null, RUBRIC, List.of(), false);
        var current = result(100, 0, 0);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.INCOMPARABLE);
    }

    @Test
    @DisplayName("给定负降幅阈值或零翻转阈值，when 构造，then 拒绝（翻转 0 会让空跑恒告警）")
    void givenIllegalThresholds_whenConstruct_thenThrows() {
        assertThatThrownBy(() -> new EvalRegressionJudge(-1, 3, 15))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalRegressionJudge(10, 0, 15))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalRegressionJudge(10, 3, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("给定集合型字段为 null（收割侧缺列），when 构造并判定，then 归一为空且同指纹可比")
    void givenNullCollectionFields_whenConstruct_thenNormalizedEmptyAndComparable() {
        var base = new RunResult(1.0, 100, 0, 0, null, BANK, null, null, false);
        var current = new RunResult(1.0, 100, 0, 0, null, BANK, null, null, true);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(current.byCategory()).isEmpty();
        assertThat(current.rubricVersions()).isEmpty();
        assertThat(current.outcomes()).isEmpty();
        assertThat(verdict.status()).isEqualTo(Status.RECOVERED);
    }

    // ———— 多命中聚合 ————

    @Test
    @DisplayName("给定降幅/翻转/分类三路同时命中，when 判定，then DEGRADED 且三类理由齐备")
    void givenMultipleHits_whenJudge_thenAllReasonsAggregated() {
        var base = result(1.0, 100, 0, 0,
                Map.of("METRIC_CALC", new int[]{20, 0, 0}), BANK, RUBRIC,
                outcomes(5, List.of()), false);
        var current = result(1.0, 85, 15, 0,
                Map.of("METRIC_CALC", new int[]{17, 3, 0}), BANK, RUBRIC,
                outcomes(5, List.of("q-1", "q-2", "q-3")), false);

        var verdict = judge.judge(current, Optional.of(base));

        assertThat(verdict.status()).isEqualTo(Status.DEGRADED);
        assertThat(verdict.reasons()).anyMatch(r -> r.startsWith("总通过率"));
        assertThat(verdict.reasons()).anyMatch(r -> r.contains("翻转"));
        assertThat(verdict.reasons()).anyMatch(r -> r.contains("METRIC_CALC"));
    }
}

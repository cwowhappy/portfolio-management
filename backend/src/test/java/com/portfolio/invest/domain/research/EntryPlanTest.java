package com.portfolio.invest.domain.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntryPlanTest {

    private static final Instant CREATED = Instant.parse("2026-09-28T08:00:00Z");

    // ---- kellyRatio（D23 简化凯利，只做算术不做估计） ----

    @DisplayName("kellyRatio 简化凯利：f* = p − (1−p)/b（0.6/2.0 → 0.4）")
    @Test
    void givenWinRateAndPayoff_whenKellyRatio_thenSimplifiedKelly() {
        var plan = EntryPlan.of(1L, new BigDecimal("0.6"), new BigDecimal("2.0"), List.of(batch("0.4")));
        // 契约公式 f* = p − (1−p)/b = 0.6 − 0.4/2.0 = 0.4
        //（brief Step1 示例值 0.2 与其自述公式算术不符，以 Interfaces 逐字契约公式为准）
        assertThat(plan.kellyRatio()).isEqualByComparingTo("0.4");

        var plan2 = EntryPlan.of(1L, new BigDecimal("0.55"), new BigDecimal("1.5"), List.of(batch("0.4")));
        // f* = 0.55 − 0.45/1.5 = 0.55 − 0.3 = 0.25
        assertThat(plan2.kellyRatio()).isEqualByComparingTo("0.25");
    }

    @DisplayName("kellyRatio 参数任一为空返回 null（D23 手动可选，不估）")
    @Test
    void givenMissingParams_whenKellyRatio_thenNull() {
        assertThat(EntryPlan.of(1L, null, new BigDecimal("2.0"), List.of(batch("0.4"))).kellyRatio()).isNull();
        assertThat(EntryPlan.of(1L, new BigDecimal("0.6"), null, List.of(batch("0.4"))).kellyRatio()).isNull();
        assertThat(EntryPlan.of(1L, null, null, List.of(batch("0.4"))).kellyRatio()).isNull();
    }

    @DisplayName("kellyRatio 负值原样返回不裁剪（无意义仓位由调用方判断）")
    @Test
    void givenNegativeKelly_whenKellyRatio_thenReturnedAsIs() {
        var plan = EntryPlan.of(1L, new BigDecimal("0.3"), new BigDecimal("2.0"), List.of(batch("0.4")));
        // f* = 0.3 − 0.7/2.0 = −0.05
        assertThat(plan.kellyRatio()).isEqualByComparingTo("-0.05");
    }

    // ---- Σratio 硬校验（D5 唯一阻断） ----

    @DisplayName("Σratio 1.01 拒绝：validateBatches 与 of 均抛 RATIO_SUM_EXCEEDED")
    @Test
    void givenRatioSumAboveOne_whenValidate_thenThrowRatioSumExceeded() {
        var batches = List.of(
                batch("0.50"),
                new EntryBatch(2, new BigDecimal("11.00"), new BigDecimal("12.00"), 100, null, new BigDecimal("0.51")));
        assertThatThrownBy(() -> EntryPlan.validateBatches(batches))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.RATIO_SUM_EXCEEDED));
        assertThatThrownBy(() -> EntryPlan.of(1L, null, null, batches))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.RATIO_SUM_EXCEEDED));
    }

    @DisplayName("Σratio 恰为 1.0 通过（> 1 才拒，compareTo 判定不看 scale）")
    @Test
    void givenRatioSumExactlyOne_whenOf_thenPass() {
        var batches = List.of(
                new EntryBatch(1, new BigDecimal("12.00"), new BigDecimal("13.50"), 200,
                        new BigDecimal("2600.00"), new BigDecimal("0.60")),
                new EntryBatch(2, new BigDecimal("11.00"), new BigDecimal("12.00"), 100, null,
                        new BigDecimal("0.40")));
        var plan = EntryPlan.of(1L, new BigDecimal("0.6"), new BigDecimal("2.0"), batches);
        assertThat(plan.batches()).hasSize(2);
        assertThat(plan.kellyRatio()).isEqualByComparingTo("0.4");
    }

    @DisplayName("validateBatches 空列表 Σ=0 通过（非空约束由 of 承担）")
    @Test
    void givenEmptyList_whenValidateBatches_thenPass() {
        EntryPlan.validateBatches(List.of());
    }

    // ---- EntryBatch 构造校验 ----

    @DisplayName("批次价格下限高于上限抛 BATCH_INVALID")
    @Test
    void givenLowAboveHigh_whenNewBatch_thenThrowBatchInvalid() {
        assertThatThrownBy(() -> new EntryBatch(1, new BigDecimal("15.00"), new BigDecimal("12.00"),
                100, null, new BigDecimal("0.4")))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.BATCH_INVALID));
    }

    @DisplayName("批次价格缺失或为负抛 BATCH_INVALID")
    @Test
    void givenMissingOrNegativePrice_whenNewBatch_thenThrowBatchInvalid() {
        assertThatThrownBy(() -> new EntryBatch(1, null, new BigDecimal("12.00"), 100, null, new BigDecimal("0.4")))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.BATCH_INVALID));
        assertThatThrownBy(() -> new EntryBatch(1, new BigDecimal("12.00"), null, 100, null, new BigDecimal("0.4")))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.BATCH_INVALID));
        assertThatThrownBy(() -> new EntryBatch(1, new BigDecimal("-0.01"), new BigDecimal("12.00"), 100, null,
                        new BigDecimal("0.4")))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.BATCH_INVALID));
    }

    @DisplayName("批次数量 ≤ 0 抛 BATCH_INVALID")
    @Test
    void givenNonPositiveQuantity_whenNewBatch_thenThrowBatchInvalid() {
        assertThatThrownBy(() -> new EntryBatch(1, new BigDecimal("12.00"), new BigDecimal("13.00"),
                0, null, new BigDecimal("0.4")))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.BATCH_INVALID));
        assertThatThrownBy(() -> new EntryBatch(1, new BigDecimal("12.00"), new BigDecimal("13.00"),
                -100, null, new BigDecimal("0.4")))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.BATCH_INVALID));
    }

    @DisplayName("批次占比 null/0/超 1 抛 BATCH_INVALID；恰为 1 合法")
    @Test
    void givenInvalidRatio_whenNewBatch_thenThrowBatchInvalid() {
        assertThatThrownBy(() -> new EntryBatch(1, new BigDecimal("12.00"), new BigDecimal("13.00"),
                100, null, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.BATCH_INVALID));
        assertThatThrownBy(() -> new EntryBatch(1, new BigDecimal("12.00"), new BigDecimal("13.00"),
                100, null, BigDecimal.ZERO))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.BATCH_INVALID));
        assertThatThrownBy(() -> new EntryBatch(1, new BigDecimal("12.00"), new BigDecimal("13.00"),
                100, null, new BigDecimal("1.01")))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.BATCH_INVALID));
        // ratio = 1 恰好合法（(0,1] 闭右端）
        assertThat(new EntryBatch(1, new BigDecimal("12.00"), new BigDecimal("13.00"), 100, null, BigDecimal.ONE)
                .ratio()).isEqualByComparingTo("1");
    }

    @DisplayName("批次价格下限等于上限合法（≤ 闭区间）；amount 可空")
    @Test
    void givenLowEqualsHighAndNullAmount_whenNewBatch_thenSucceed() {
        var b = new EntryBatch(1, new BigDecimal("12.50"), new BigDecimal("12.50"), 100, null,
                new BigDecimal("0.4"));
        assertThat(b.priceLow()).isEqualByComparingTo("12.5");
        assertThat(b.priceHigh()).isEqualByComparingTo("12.5");
        assertThat(b.amount()).isNull();
    }

    // ---- EntryPlan.of 校验 ----

    @DisplayName("of 空批次抛 BATCH_REQUIRED（至少一批）")
    @Test
    void givenEmptyBatches_whenOf_thenThrowBatchRequired() {
        assertThatThrownBy(() -> EntryPlan.of(1L, null, null, List.of()))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.BATCH_REQUIRED));
        assertThatThrownBy(() -> EntryPlan.of(1L, null, null, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.BATCH_REQUIRED));
    }

    @DisplayName("of 缺项目抛 PROJECT_REQUIRED")
    @Test
    void givenNullProjectId_whenOf_thenThrowProjectRequired() {
        assertThatThrownBy(() -> EntryPlan.of(null, new BigDecimal("0.6"), new BigDecimal("2.0"),
                List.of(batch("0.4"))))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.PROJECT_REQUIRED));
    }

    @DisplayName("winRate 越界（0/1/1.2/负）抛 KELLY_PARAM_INVALID")
    @Test
    void givenWinRateOutOfRange_whenOf_thenThrowKellyParamInvalid() {
        for (BigDecimal bad : new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ONE,
                new BigDecimal("1.2"), new BigDecimal("-0.1")}) {
            assertThatThrownBy(() -> EntryPlan.of(1L, bad, new BigDecimal("2.0"), List.of(batch("0.4"))))
                    .isInstanceOfSatisfying(ResearchException.class,
                            e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.KELLY_PARAM_INVALID));
        }
    }

    @DisplayName("payoffRatio 非正抛 KELLY_PARAM_INVALID；winRate/payoff 均空合法（D23 手动可选）")
    @Test
    void givenNonPositivePayoff_whenOf_thenThrowKellyParamInvalid() {
        assertThatThrownBy(() -> EntryPlan.of(1L, new BigDecimal("0.6"), BigDecimal.ZERO, List.of(batch("0.4"))))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.KELLY_PARAM_INVALID));
        assertThatThrownBy(() -> EntryPlan.of(1L, new BigDecimal("0.6"), new BigDecimal("-2.0"),
                List.of(batch("0.4"))))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.KELLY_PARAM_INVALID));
        // 两参数均可空（D23 手动可选，kellyRatio 返回 null）
        assertThat(EntryPlan.of(1L, null, null, List.of(batch("0.4"))).kellyRatio()).isNull();
    }

    // ---- 构造与还原 ----

    @DisplayName("of 构造：字段贯通 + 批次列表防御性拷贝")
    @Test
    void givenValidInput_whenOf_thenFieldsCarriedWithDefensiveCopy() {
        var source = new ArrayList<>(List.of(batch("0.4")));
        var plan = EntryPlan.of(1L, new BigDecimal("0.6"), new BigDecimal("2.0"), source);
        assertThat(plan.id()).isNull();
        assertThat(plan.projectId()).isEqualTo(1L);
        assertThat(plan.winRate()).isEqualByComparingTo("0.6");
        assertThat(plan.payoffRatio()).isEqualByComparingTo("2.0");
        assertThat(plan.batches()).hasSize(1);
        assertThat(plan.version()).isNull();
        assertThat(plan.createdAt()).isNotNull();
        assertThat(plan.updatedAt()).isNotNull();
        // 防御性拷贝：构造后改源列表不影响计划
        source.add(batch("0.3"));
        assertThat(plan.batches()).hasSize(1);
    }

    @DisplayName("reconstitute 全参还原持久化状态（不做校验）")
    @Test
    void givenAllFields_whenReconstitute_thenCarriedThrough() {
        var batches = List.of(new EntryBatch(1, new BigDecimal("12.0"), new BigDecimal("13.5"), 200,
                new BigDecimal("2600.00"), new BigDecimal("0.4")));
        var plan = EntryPlan.reconstitute(21L, 1L, new BigDecimal("0.6000"), new BigDecimal("2.0000"),
                batches, 3L, CREATED, CREATED.plusSeconds(90));
        assertThat(plan.id()).isEqualTo(21L);
        assertThat(plan.projectId()).isEqualTo(1L);
        assertThat(plan.winRate()).isEqualByComparingTo("0.6");
        assertThat(plan.payoffRatio()).isEqualByComparingTo("2.0");
        assertThat(plan.batches()).isEqualTo(batches);
        assertThat(plan.version()).isEqualTo(3L);
        assertThat(plan.createdAt()).isEqualTo(CREATED);
        assertThat(plan.updatedAt()).isEqualTo(CREATED.plusSeconds(90));
    }

    private static EntryBatch batch(String ratio) {
        return new EntryBatch(1, new BigDecimal("12.00"), new BigDecimal("13.50"), 200,
                new BigDecimal("2600.00"), new BigDecimal(ratio));
    }
}

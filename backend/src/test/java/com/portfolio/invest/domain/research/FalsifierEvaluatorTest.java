package com.portfolio.invest.domain.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D10/D21 证伪条件规则式求值纯函数：四谓词方向严格比较（恰等于阈值不命中）、
 * 对应值缺失 → skipped「无最近价/估值」不自动命中、EVENT → pending「待人工勾选」、
 * 停用条件不产出；命中 basis 含数字与口径尾注（可解释）。
 */
class FalsifierEvaluatorTest {

    private static final String NOTE_PRICE = "东财收盘 2026-09-26";
    private static final String NOTE_VALUATION = "东财估值 2026-09-26";

    private static Falsifier predicate(FalsifierPredicate p, String threshold) {
        return Falsifier.ofPredicate(9L, p, new BigDecimal(threshold), null);
    }

    private static Falsifier disabledPredicate(FalsifierPredicate p, String threshold) {
        return Falsifier.reconstitute(4L, 9L, FalsifierKind.PREDICATE, p,
                new BigDecimal(threshold), false, null, false, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static MarketSnapshot snapshot(String close, String pe, String pb, String note) {
        return new MarketSnapshot(
                close == null ? null : new BigDecimal(close),
                pe == null ? null : new BigDecimal(pe),
                pb == null ? null : new BigDecimal(pb),
                note);
    }

    // ---- 四谓词方向 ----

    @DisplayName("PRICE_BELOW：收盘价跌破下限 → 命中，basis 含数字与口径（D10 可解释）")
    @Test
    void givenCloseBelowThreshold_whenEvaluate_thenHitWithExplainableBasis() {
        var results = FalsifierEvaluator.evaluate(
                List.of(predicate(FalsifierPredicate.PRICE_BELOW, "13.00")),
                snapshot("12.34", "25.6", "3.5", NOTE_PRICE));

        assertThat(results).hasSize(1);
        var result = results.get(0);
        assertThat(result.hit()).isTrue();
        assertThat(result.pending()).isFalse();
        assertThat(result.skipped()).isFalse();
        assertThat(result.basis()).isEqualTo("收盘价 12.34 < 下限 13.00（东财收盘 2026-09-26）");
        assertThat(result.falsifier().threshold()).isEqualByComparingTo("13");
    }

    @DisplayName("PRICE_ABOVE：收盘价升破上限 → 命中，basis 同构标注口径")
    @Test
    void givenCloseAboveThreshold_whenEvaluate_thenHit() {
        var results = FalsifierEvaluator.evaluate(
                List.of(predicate(FalsifierPredicate.PRICE_ABOVE, "14.00")),
                snapshot("15.00", null, null, NOTE_PRICE));

        assertThat(results).hasSize(1);
        var result = results.get(0);
        assertThat(result.hit()).isTrue();
        assertThat(result.skipped()).isFalse();
        assertThat(result.basis()).isEqualTo("收盘价 15.00 > 上限 14.00（东财收盘 2026-09-26）");
    }

    @DisplayName("PE_ABOVE：PE 超阈 → 命中，basis 用估值口径尾注")
    @Test
    void givenPeAboveThreshold_whenEvaluate_thenHit() {
        var results = FalsifierEvaluator.evaluate(
                List.of(predicate(FalsifierPredicate.PE_ABOVE, "20")),
                snapshot("12.34", "25.6", null, NOTE_VALUATION));

        assertThat(results).hasSize(1);
        var result = results.get(0);
        assertThat(result.hit()).isTrue();
        assertThat(result.skipped()).isFalse();
        assertThat(result.basis()).isEqualTo("PE 25.6 > 上限 20（东财估值 2026-09-26）");
    }

    @DisplayName("PB_ABOVE：PB 超阈 → 命中，basis 同构")
    @Test
    void givenPbAboveThreshold_whenEvaluate_thenHit() {
        var results = FalsifierEvaluator.evaluate(
                List.of(predicate(FalsifierPredicate.PB_ABOVE, "3")),
                snapshot("12.34", null, "3.50", NOTE_VALUATION));

        assertThat(results).hasSize(1);
        var result = results.get(0);
        assertThat(result.hit()).isTrue();
        assertThat(result.skipped()).isFalse();
        assertThat(result.basis()).isEqualTo("PB 3.50 > 上限 3（东财估值 2026-09-26）");
    }

    @DisplayName("四方向边界：值恰等于阈值 → 严格比较不命中（未命中 basis 用 ≥/≤ 镜像算符）")
    @Test
    void givenValueEqualToThreshold_whenEvaluate_thenNotHit() {
        var falsifiers = List.of(
                predicate(FalsifierPredicate.PRICE_BELOW, "13.00"),
                predicate(FalsifierPredicate.PRICE_ABOVE, "13.00"),
                predicate(FalsifierPredicate.PE_ABOVE, "25.6"),
                predicate(FalsifierPredicate.PB_ABOVE, "3.5"));
        var snapshot = snapshot("13.00", "25.6", "3.5", NOTE_PRICE);

        var results = FalsifierEvaluator.evaluate(falsifiers, snapshot);

        assertThat(results).hasSize(4).extracting(FalsifierHitResult::hit).containsOnly(false);
        assertThat(results).extracting(FalsifierHitResult::skipped).containsOnly(false);
        assertThat(results).extracting(FalsifierHitResult::pending).containsOnly(false);
        assertThat(results).extracting(FalsifierHitResult::basis).containsExactly(
                "收盘价 13.00 ≥ 下限 13.00（东财收盘 2026-09-26）",
                "收盘价 13.00 ≤ 上限 13.00（东财收盘 2026-09-26）",
                "PE 25.6 ≤ 上限 25.6（东财收盘 2026-09-26）",
                "PB 3.5 ≤ 上限 3.5（东财收盘 2026-09-26）");
    }

    // ---- 缺数据跳过（Review Focus 4） ----

    @DisplayName("对应值缺失（收盘价/估值不可得）→ skipped 条目 basis「无最近价/估值」，不算命中不算 pending；可得口径正常求值")
    @Test
    void givenMissingSnapshotValues_whenEvaluate_thenSkippedEntries() {
        var falsifiers = List.of(
                predicate(FalsifierPredicate.PRICE_BELOW, "13.00"),
                predicate(FalsifierPredicate.PE_ABOVE, "20"),
                predicate(FalsifierPredicate.PB_ABOVE, "3"));
        var snapshot = snapshot(null, "25.6", null, NOTE_VALUATION);

        var results = FalsifierEvaluator.evaluate(falsifiers, snapshot);

        assertThat(results).hasSize(3);
        var price = results.get(0);
        assertThat(price.skipped()).isTrue();
        assertThat(price.hit()).isFalse();
        assertThat(price.pending()).isFalse();
        assertThat(price.basis()).isEqualTo("无最近价/估值");
        // PE 可得仍正常命中，不被缺失口径波及
        assertThat(results.get(1).hit()).isTrue();
        assertThat(results.get(2).skipped()).isTrue();
        assertThat(results.get(2).basis()).isEqualTo("无最近价/估值");
    }

    @DisplayName("快照整体缺失 → 全部谓词 skipped（不抛异常，日终扫描容错）；EVENT 仍 pending")
    @Test
    void givenNullSnapshot_whenEvaluate_thenAllPredicatesSkipped() {
        var falsifiers = List.of(
                predicate(FalsifierPredicate.PRICE_BELOW, "13.00"),
                Falsifier.ofEvent(9L, "业绩连续两季低于预期"));

        var results = FalsifierEvaluator.evaluate(falsifiers, null);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).skipped()).isTrue();
        assertThat(results.get(0).basis()).isEqualTo("无最近价/估值");
        assertThat(results.get(1).pending()).isTrue();
    }

    // ---- EVENT 待人工勾选（不自动判命中） ----

    @DisplayName("EVENT 事件类 → pending「待人工勾选」，不自动判命中（D10 人工确认）")
    @Test
    void givenEventFalsifier_whenEvaluate_thenPendingWithoutAutoHit() {
        var results = FalsifierEvaluator.evaluate(
                List.of(Falsifier.ofEvent(9L, "业绩连续两季低于预期")),
                snapshot("12.34", "25.6", "3.5", NOTE_PRICE));

        assertThat(results).hasSize(1);
        var result = results.get(0);
        assertThat(result.pending()).isTrue();
        assertThat(result.hit()).isFalse();
        assertThat(result.skipped()).isFalse();
        assertThat(result.basis()).isEqualTo("待人工勾选");
    }

    // ---- 过滤与入参容错 ----

    @DisplayName("enabled=false 条件跳过不产出条目（与 DisciplineCheckService 同口径）")
    @Test
    void givenDisabledFalsifiers_whenEvaluate_thenNoEntry() {
        var falsifiers = List.of(
                disabledPredicate(FalsifierPredicate.PB_ABOVE, "3"),
                predicate(FalsifierPredicate.PRICE_BELOW, "13.00"));
        var results = FalsifierEvaluator.evaluate(falsifiers, snapshot("12.34", null, null, NOTE_PRICE));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).falsifier().predicate()).isEqualTo(FalsifierPredicate.PRICE_BELOW);
        assertThat(results.get(0).hit()).isTrue();
    }

    @DisplayName("条件列表为 null 或空 → 空结果（未配置证伪条件是合法状态）")
    @Test
    void givenNullOrEmptyFalsifiers_whenEvaluate_thenEmpty() {
        var snapshot = snapshot("12.34", "25.6", "3.5", NOTE_PRICE);
        assertThat(FalsifierEvaluator.evaluate(null, snapshot)).isEmpty();
        assertThat(FalsifierEvaluator.evaluate(List.of(), snapshot)).isEmpty();
    }

    @DisplayName("priceNote 缺失 → basis 不带口径尾注（文案拼接容错）")
    @Test
    void givenSnapshotWithoutNote_whenEvaluate_thenBasisWithoutNoteSuffix() {
        var results = FalsifierEvaluator.evaluate(
                List.of(predicate(FalsifierPredicate.PRICE_BELOW, "13.00")),
                snapshot("12.34", null, null, null));

        assertThat(results.get(0).hit()).isTrue();
        assertThat(results.get(0).basis()).isEqualTo("收盘价 12.34 < 下限 13.00");
    }
}

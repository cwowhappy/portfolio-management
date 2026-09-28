package com.portfolio.invest.domain.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F09/F10/F12 纪律检查纯函数（NFR-3）：三态语义（越线 HIT / 未越线 PASS / 规则缺失 UNSET 中性）、
 * F01 必查项布尔条目（勾选 PASS、未勾选 HIT）、SELL/REDUCE 追加证伪「待核对」条目（BUY/ADD 不注入）。
 */
class DisciplineCheckServiceTest {

    private static CheckContext ctx(BigDecimal single, BigDecimal industry, BigDecimal pe, BigDecimal pb,
                                    List<String> f01, List<Falsifier> falsifiers) {
        return new CheckContext(single, industry, pe, pb, f01, falsifiers);
    }

    private static CheckItemResult item(List<CheckItemResult> items, String metric) {
        return items.stream().filter(i -> metric.equals(i.metric())).findFirst().orElseThrow();
    }

    private static Falsifier predicateFalsifier(FalsifierPredicate predicate, String threshold) {
        return Falsifier.ofPredicate(9L, predicate, new BigDecimal(threshold), null);
    }

    private static Falsifier disabledFalsifier() {
        return Falsifier.reconstitute(3L, 9L, FalsifierKind.PREDICATE, FalsifierPredicate.PB_ABOVE,
                new BigDecimal("8"), false, null, false, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    // ---- 规则项三态 ----

    @DisplayName("规则内不越线 → PASS（阈值与当前值回显，compareTo 判定不看 scale）")
    @Test
    void givenValueWithinRule_whenCheck_thenPass() {
        var context = ctx(new BigDecimal("0.10"), null, new BigDecimal("28"), null, null, null);
        var items = DisciplineCheckService.check(context, CheckType.BUY, List.of(
                new RuleInput(DisciplineCheckService.METRIC_SINGLE_POSITION_RATIO, new BigDecimal("0.2000")),
                new RuleInput(DisciplineCheckService.METRIC_STOCK_PE_MAX, new BigDecimal("30"))));

        var single = item(items, DisciplineCheckService.METRIC_SINGLE_POSITION_RATIO);
        assertThat(single.outcome()).isEqualTo(CheckOutcome.PASS);
        assertThat(single.threshold()).isEqualByComparingTo("0.2");
        assertThat(single.currentValue()).isEqualByComparingTo("0.1");
        assertThat(item(items, DisciplineCheckService.METRIC_STOCK_PE_MAX).outcome()).isEqualTo(CheckOutcome.PASS);
    }

    @DisplayName("越线 → HIT（严格大于上限才命中，恰等于上限仍 PASS）")
    @Test
    void givenValueOverRule_whenCheck_thenHit() {
        var context = ctx(new BigDecimal("0.30"), new BigDecimal("0.25"), null, null, null, null);
        var items = DisciplineCheckService.check(context, CheckType.ADD, List.of(
                new RuleInput(DisciplineCheckService.METRIC_SINGLE_POSITION_RATIO, new BigDecimal("0.20")),
                new RuleInput(DisciplineCheckService.METRIC_INDUSTRY_POSITION_RATIO, new BigDecimal("0.25"))));

        var single = item(items, DisciplineCheckService.METRIC_SINGLE_POSITION_RATIO);
        assertThat(single.outcome()).isEqualTo(CheckOutcome.HIT);
        assertThat(single.currentValue()).isEqualByComparingTo("0.3");
        // 行业占比 0.25 恰等于上限 0.25：不越线
        assertThat(item(items, DisciplineCheckService.METRIC_INDUSTRY_POSITION_RATIO).outcome())
                .isEqualTo(CheckOutcome.PASS);
    }

    @DisplayName("规则缺失的指标 → UNSET「未设定」中性项（阈值/当前值均空，非 PASS 非 HIT）")
    @Test
    void givenNoRuleForMetric_whenCheck_thenUnsetNeutral() {
        var context = ctx(new BigDecimal("0.10"), null, new BigDecimal("28"), null, null, null);
        var items = DisciplineCheckService.check(context, CheckType.BUY, List.of(
                new RuleInput(DisciplineCheckService.METRIC_STOCK_PE_MAX, new BigDecimal("30"))));

        var single = item(items, DisciplineCheckService.METRIC_SINGLE_POSITION_RATIO);
        assertThat(single.outcome()).isEqualTo(CheckOutcome.UNSET);
        assertThat(single.threshold()).isNull();
        assertThat(single.currentValue()).isNull();
        assertThat(single.outcome()).isNotIn(CheckOutcome.PASS, CheckOutcome.HIT);
        // 已配置的 PE 仍正常比较，不被未设定指标影响
        assertThat(item(items, DisciplineCheckService.METRIC_STOCK_PE_MAX).outcome()).isEqualTo(CheckOutcome.PASS);
    }

    @DisplayName("规则集为空 → 四指标全 UNSET（未配置规则是合法状态，不抛异常）")
    @Test
    void givenNullRules_whenCheck_thenAllRuleItemsUnset() {
        var items = DisciplineCheckService.check(ctx(new BigDecimal("0.10"), null, null, null, null, null),
                CheckType.BUY, null);
        assertThat(items).extracting(CheckItemResult::metric)
                .contains(DisciplineCheckService.METRIC_SINGLE_POSITION_RATIO,
                        DisciplineCheckService.METRIC_INDUSTRY_POSITION_RATIO,
                        DisciplineCheckService.METRIC_STOCK_PE_MAX,
                        DisciplineCheckService.METRIC_STOCK_PB_MAX);
        assertThat(items).extracting(CheckItemResult::outcome).containsOnly(CheckOutcome.UNSET, CheckOutcome.HIT);
        // 四规则项全 UNSET（其余 HIT 来自 F01 未勾选，见下）
        assertThat(items.stream().filter(i -> i.outcome() == CheckOutcome.UNSET)).hasSize(4);
    }

    @DisplayName("规则已配置但指标值缺失（行情/估值不可得）→ 跳过该条目不产出")
    @Test
    void givenRuleWithoutCurrentValue_whenCheck_thenItemSkipped() {
        var context = ctx(new BigDecimal("0.10"), null, null, null, null, null);
        var items = DisciplineCheckService.check(context, CheckType.BUY, List.of(
                new RuleInput(DisciplineCheckService.METRIC_STOCK_PE_MAX, new BigDecimal("30"))));

        assertThat(items).extracting(CheckItemResult::metric)
                .doesNotContain(DisciplineCheckService.METRIC_STOCK_PE_MAX);
        // 3 个比值类指标（单票已配 PASS，行业/PB 未配 UNSET）+ 4 个 F01 项
        assertThat(items).hasSize(7);
    }

    @DisplayName("输出顺序固定：规则项（单票/行业/PE/PB）→ F01 必查项 → 证伪核对项")
    @Test
    void givenFullInputs_whenCheck_thenDeterministicOrder() {
        var context = ctx(new BigDecimal("0.10"), null, null, null,
                List.of(DisciplineCheckService.F01_CIRCLE_OF_COMPETENCE), List.of(predicateFalsifier(FalsifierPredicate.PRICE_BELOW, "13.00")));
        var items = DisciplineCheckService.check(context, CheckType.SELL, List.of(
                new RuleInput(DisciplineCheckService.METRIC_SINGLE_POSITION_RATIO, new BigDecimal("0.20"))));

        assertThat(items).extracting(CheckItemResult::metric).containsExactly(
                DisciplineCheckService.METRIC_SINGLE_POSITION_RATIO,
                DisciplineCheckService.METRIC_INDUSTRY_POSITION_RATIO,
                DisciplineCheckService.METRIC_STOCK_PE_MAX,
                DisciplineCheckService.METRIC_STOCK_PB_MAX,
                DisciplineCheckService.F01_CIRCLE_OF_COMPETENCE,
                DisciplineCheckService.F01_SAFETY_MARGIN,
                DisciplineCheckService.F01_VALUATION_CHECK,
                DisciplineCheckService.F01_BUY_CONDITIONS,
                "PRICE_BELOW 13.00");
    }

    // ---- F01 必查项布尔条目 ----

    @DisplayName("F01 必查项勾选 → PASS、未勾选 → HIT（全类型注入，未识别的勾选串忽略）")
    @Test
    void givenPartiallyCheckedF01_whenCheck_thenUncheckedHit() {
        var context = ctx(new BigDecimal("0.10"), null, null, null,
                List.of(DisciplineCheckService.F01_CIRCLE_OF_COMPETENCE,
                        DisciplineCheckService.F01_SAFETY_MARGIN,
                        DisciplineCheckService.F01_VALUATION_CHECK,
                        "不在清单中的自定义项"), null);
        var items = DisciplineCheckService.check(context, CheckType.BUY, null);

        assertThat(item(items, DisciplineCheckService.F01_CIRCLE_OF_COMPETENCE).outcome()).isEqualTo(CheckOutcome.PASS);
        assertThat(item(items, DisciplineCheckService.F01_SAFETY_MARGIN).outcome()).isEqualTo(CheckOutcome.PASS);
        assertThat(item(items, DisciplineCheckService.F01_VALUATION_CHECK).outcome()).isEqualTo(CheckOutcome.PASS);
        assertThat(item(items, DisciplineCheckService.F01_BUY_CONDITIONS).outcome()).isEqualTo(CheckOutcome.HIT);
        // 未识别勾选串不产出额外条目：4 规则 UNSET + 4 F01
        assertThat(items).hasSize(8);
    }

    // ---- 证伪核对条目（F12） ----

    @DisplayName("SELL：启用证伪条件逐条注入「待核对」条目（outcome=UNSET，停用条件不注入）")
    @Test
    void givenSellWithFalsifiers_whenCheck_thenPendingItemsPerEnabledFalsifier() {
        var context = ctx(new BigDecimal("0.10"), null, null, null, null, List.of(
                predicateFalsifier(FalsifierPredicate.PRICE_BELOW, "13.00"),
                Falsifier.ofEvent(9L, "业绩连续两季低于预期"),
                disabledFalsifier()));
        var items = DisciplineCheckService.check(context, CheckType.SELL, null);

        var price = item(items, "PRICE_BELOW 13.00");
        assertThat(price.outcome()).isEqualTo(CheckOutcome.UNSET);
        assertThat(price.threshold()).isEqualByComparingTo("13");
        assertThat(price.currentValue()).isNull();
        var event = item(items, "业绩连续两季低于预期");
        assertThat(event.outcome()).isEqualTo(CheckOutcome.UNSET);
        assertThat(event.threshold()).isNull();
        // 停用条件不产出条目
        assertThat(items).extracting(CheckItemResult::metric).doesNotContain("PB_ABOVE 8");
    }

    @DisplayName("REDUCE 同 SELL 注入证伪核对条目；BUY/ADD 不注入（F12 只约束减仓/卖出）")
    @Test
    void givenEachCheckType_whenCheck_thenFalsifierItemsOnlyForSellAndReduce() {
        var context = ctx(new BigDecimal("0.10"), null, null, null, null,
                List.of(predicateFalsifier(FalsifierPredicate.PRICE_BELOW, "13.00")));
        var falsifierMetrics = List.of("PRICE_BELOW 13.00");

        for (CheckType type : CheckType.values()) {
            var items = DisciplineCheckService.check(context, type, null);
            boolean contains = items.stream().anyMatch(i -> falsifierMetrics.contains(i.metric()));
            if (type == CheckType.SELL || type == CheckType.REDUCE) {
                assertThat(contains).as("SELL/REDUCE 应含证伪核对条目（%s）", type).isTrue();
            } else {
                assertThat(contains).as("BUY/ADD 不应含证伪核对条目（%s）", type).isFalse();
                // 无证伪条目时：4 规则 UNSET + 4 F01 = 8
                assertThat(items).as(type.name()).hasSize(8);
            }
        }
    }

    // ---- 入参与 RuleInput 构造 ----

    @DisplayName("ctx/type 缺失 → CHECK_CONTEXT_REQUIRED / CHECK_TYPE_REQUIRED")
    @Test
    void givenNullContextOrType_whenCheck_thenThrow() {
        var context = ctx(new BigDecimal("0.10"), null, null, null, null, null);
        assertThatThrownBy(() -> DisciplineCheckService.check(null, CheckType.BUY, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.CHECK_CONTEXT_REQUIRED));
        assertThatThrownBy(() -> DisciplineCheckService.check(context, null, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.CHECK_TYPE_REQUIRED));
    }

    @DisplayName("RuleInput 指标空白/阈值缺失 → METRIC_REQUIRED / THRESHOLD_INVALID")
    @Test
    void givenMalformedRuleInput_whenConstruct_thenThrow() {
        assertThatThrownBy(() -> new RuleInput(" ", new BigDecimal("0.2")))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.METRIC_REQUIRED));
        assertThatThrownBy(() -> new RuleInput(DisciplineCheckService.METRIC_STOCK_PE_MAX, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.THRESHOLD_INVALID));
    }
}

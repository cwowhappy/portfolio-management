package com.portfolio.invest.domain.allocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class RebalanceCalculatorTest {

    private static final Instant TODAY = Instant.parse("2026-09-16T00:00:00Z");

    private static Map<AssetClass, BigDecimal> permanent() { // 永久组合 25×4（REITS 0）
        return Map.of(AssetClass.STOCK, new BigDecimal("25"), AssetClass.BOND, new BigDecimal("25"),
                AssetClass.GOLD, new BigDecimal("25"), AssetClass.CASH, new BigDecimal("25"));
    }

    private static Map<AssetClass, BigDecimal> onlyCash() { // 全现金实际配置
        return Map.of(AssetClass.CASH, new BigDecimal("100"));
    }

    @DisplayName("阈值触发与金额建议：全现金 vs 永久组合，建议金额守恒")
    @Test
    void givenAllCashVsPermanent_whenCalculate_thenThresholdBreachedAndAmountsConserved() {
        var r = RebalanceCalculator.calculate(permanent(), onlyCash(), new BigDecimal("10000"),
                RebalanceFrequency.OFF, null, TODAY);

        assertThat(r.anyAlert()).isTrue();
        assertThat(r.suppressed()).isFalse();

        var stock = r.items().stream().filter(i -> i.assetClass() == AssetClass.STOCK).findFirst().orElseThrow();
        assertThat(stock.targetWeight()).isEqualByComparingTo("25");
        assertThat(stock.actualWeight()).isEqualByComparingTo("0");
        assertThat(stock.deviation()).isEqualByComparingTo("-25");
        assertThat(stock.thresholdBreached()).isTrue();          // |−25| ≥ 5
        assertThat(stock.targetAmount()).isEqualByComparingTo("2500");
        assertThat(stock.currentAmount()).isEqualByComparingTo("0");
        assertThat(stock.suggestedAmount()).isEqualByComparingTo("2500"); // 买入 2500

        var cash = r.items().stream().filter(i -> i.assetClass() == AssetClass.CASH).findFirst().orElseThrow();
        assertThat(cash.suggestedAmount()).isEqualByComparingTo("-7500"); // 卖出（减持现金）7500

        // Σ建议 ≡ 0（±0.01 容差）——验收标准守恒断言
        BigDecimal sum = r.items().stream().map(RebalanceResult.Item::suggestedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isCloseTo(BigDecimal.ZERO, org.assertj.core.data.Offset.offset(new BigDecimal("0.01")));

        assertThat(r.timeTrigger().triggered()).isFalse(); // OFF 不触发
    }

    @DisplayName("偏离恰 5pp 触发、4.99 不触发（边界）")
    @Test
    void givenDeviationAtBoundary_whenCalculate_thenTriggerOnlyAtOrAbove5pp() {
        var target = Map.of(AssetClass.STOCK, new BigDecimal("60"), AssetClass.CASH, new BigDecimal("40"));
        var actual = Map.of(AssetClass.STOCK, new BigDecimal("65"), AssetClass.CASH, new BigDecimal("35"));
        var r = RebalanceCalculator.calculate(target, actual, new BigDecimal("1000"),
                RebalanceFrequency.OFF, null, TODAY);
        assertThat(r.items().get(0).thresholdBreached()).isTrue();  // 恰 5.00

        var actual49 = Map.of(AssetClass.STOCK, new BigDecimal("64.99"), AssetClass.CASH, new BigDecimal("35.01"));
        var r2 = RebalanceCalculator.calculate(target, actual49, new BigDecimal("1000"),
                RebalanceFrequency.OFF, null, TODAY);
        assertThat(r2.anyAlert()).isFalse();
    }

    @DisplayName("时间触发：季度锚点 90 天到期触发、89 天不触发；anchor 为 null 防御性不触发")
    @Test
    void givenQuarterlyFrequency_whenCalculate_thenTimeTriggerRespectsBoundary() {
        var anchor89 = TODAY.minus(java.time.Duration.ofDays(89));
        var anchor90 = TODAY.minus(java.time.Duration.ofDays(90));
        var target = Map.of(AssetClass.CASH, new BigDecimal("100"));
        var actual = Map.of(AssetClass.CASH, new BigDecimal("100"));

        var notDue = RebalanceCalculator.calculate(target, actual, new BigDecimal("1000"),
                RebalanceFrequency.QUARTERLY, anchor89, TODAY);
        assertThat(notDue.timeTrigger().triggered()).isFalse();

        var due = RebalanceCalculator.calculate(target, actual, new BigDecimal("1000"),
                RebalanceFrequency.QUARTERLY, anchor90, TODAY);
        assertThat(due.timeTrigger().triggered()).isTrue();
        assertThat(due.timeTrigger().daysOverdue()).isZero();
        assertThat(due.anyAlert()).isTrue();

        var noAnchor = RebalanceCalculator.calculate(target, actual, new BigDecimal("1000"),
                RebalanceFrequency.SEMIANNUAL, null, TODAY);
        assertThat(noAnchor.timeTrigger().triggered()).isFalse(); // 防御：正常链路 V14 已回填
    }

    @DisplayName("空组合（T=0）抑制全部触发与金额")
    @Test
    void givenZeroTotalAssets_whenCalculate_thenSuppressed() {
        var r = RebalanceCalculator.calculate(permanent(), Map.of(), BigDecimal.ZERO,
                RebalanceFrequency.SEMIANNUAL, TODAY.minus(java.time.Duration.ofDays(365)), TODAY);
        assertThat(r.suppressed()).isTrue();
        assertThat(r.anyAlert()).isFalse();
        assertThat(r.timeTrigger().triggered()).isFalse();
    }

    @DisplayName("未配置类别目标记 0（BOND/GOLD/REITS 恒 0 建议买入口径由权重差表达）")
    @Test
    void givenPartialTarget_whenCalculate_thenMissingClassesTreatedAsZero() {
        var target = Map.of(AssetClass.STOCK, new BigDecimal("100"));
        var actual = Map.of(AssetClass.STOCK, new BigDecimal("40"), AssetClass.CASH, new BigDecimal("60"));
        var r = RebalanceCalculator.calculate(target, actual, new BigDecimal("1000"),
                RebalanceFrequency.OFF, null, TODAY);
        assertThat(r.items()).hasSize(AssetClass.values().length); // 5 类全输出
        var cash = r.items().stream().filter(i -> i.assetClass() == AssetClass.CASH).findFirst().orElseThrow();
        assertThat(cash.targetWeight()).isEqualByComparingTo("0");
        assertThat(cash.suggestedAmount()).isEqualByComparingTo("-600");
    }
}

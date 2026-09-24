package com.portfolio.invest.domain.analytics;

import com.portfolio.invest.domain.analytics.AttributionCalculator.DailyRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AttributionCalculatorTest {

    /** 手算样例（rf=0）：组合 A 60%·B 30%·现金 10%；组合行业收益 A 3%/B −1%；
     * 基准 A 50%·B 50%，行业收益 A 2%/B −1% → Rb=0.5%，Rp=1.5%，excess=1%。
     * 配置A=(0.6−0.5)(2%−0.5%)=0.15%；选择A=0.6×1%=0.6%；
     * 配置B=(0.3−0.5)(−1%−0.5%)=0.3%；选择B=0.3×0%=0；现金=0.1×(0−0.5%)=−0.05%；Σ=1%=excess。 */
    @DisplayName("已知答案：两行业一日样本，配置+选择+现金=超额，residual≈0")
    @Test
    void givenTwoIndustriesOneDay_whenAttribute_thenSumsToExcess() {
        DailyRow row = new DailyRow(LocalDate.of(2026, 1, 6),
                Map.of("801010", bd("0.6"), "801030", bd("0.3")),
                Map.of("801010", bd("0.5"), "801030", bd("0.5")),
                Map.of("801010", bd("0.03"), "801030", bd("-0.01")),
                Map.of("801010", bd("0.02"), "801030", bd("-0.01")),
                bd("0.015"), bd("0.005"), bd("0.1"), BigDecimal.ZERO);
        var res = AttributionCalculator.attribute(List.of(row));
        assertThat(res.totalExcess()).isCloseTo(bd("0.01"), within(bd("0.0000001")));
        var a = res.rows().stream().filter(r -> r.industry().equals("801010")).findFirst().orElseThrow();
        assertThat(a.allocation()).isCloseTo(bd("0.0015"), within(bd("0.0000001")));
        assertThat(a.selection()).isCloseTo(bd("0.006"), within(bd("0.0000001")));
        assertThat(res.cashAllocation()).isCloseTo(bd("-0.0005"), within(bd("0.0000001")));
        assertThat(res.residual()).isCloseTo(BigDecimal.ZERO, within(bd("0.0000001")));
    }

    @DisplayName("两日累加：同构样本×2 → 各贡献翻倍")
    @Test
    void givenTwoDays_whenAttribute_thenAccumulates() {
        DailyRow d1 = sampleRow();
        DailyRow d2 = sampleRow();
        var res = AttributionCalculator.attribute(List.of(d1, d2));
        assertThat(res.totalExcess()).isCloseTo(bd("0.02"), within(bd("0.0000001")));
        var a = res.rows().stream().filter(r -> r.industry().equals("801010")).findFirst().orElseThrow();
        assertThat(a.allocation()).isCloseTo(bd("0.003"), within(bd("0.0000001")));
        assertThat(a.selection()).isCloseTo(bd("0.012"), within(bd("0.0000001")));
        assertThat(res.cashAllocation()).isCloseTo(bd("-0.001"), within(bd("0.0000001")));
    }

    @DisplayName("空序列 → 全空结果不抛异常")
    @Test
    void givenEmpty_whenAttribute_thenEmpty() {
        var res = AttributionCalculator.attribute(List.of());
        assertThat(res.rows()).isEmpty();
        assertThat(res.totalExcess()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** 第一用例同构样本（单日超额 1%）。 */
    private static DailyRow sampleRow() {
        return new DailyRow(LocalDate.of(2026, 1, 6),
                Map.of("801010", bd("0.6"), "801030", bd("0.3")),
                Map.of("801010", bd("0.5"), "801030", bd("0.5")),
                Map.of("801010", bd("0.03"), "801030", bd("-0.01")),
                Map.of("801010", bd("0.02"), "801030", bd("-0.01")),
                bd("0.015"), bd("0.005"), bd("0.1"), BigDecimal.ZERO);
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}

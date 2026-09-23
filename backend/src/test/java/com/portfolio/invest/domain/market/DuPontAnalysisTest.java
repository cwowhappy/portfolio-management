package com.portfolio.invest.domain.market;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DuPontAnalysisTest {

    @DisplayName("三因子完整：权益乘数=1/(1-资产负债率/100)，周转率=ROA%/净利率")
    @Test
    void givenCompleteInputs_whenOf_thenAllFactorsComputed() {
        var r = DuPontAnalysis.of(0.25, 5.0, 60.0, 15.0, "2026-06-30", "2026-06-30");
        assertThat(r.equityMultiplier()).isEqualTo(1.0 / (1.0 - 0.60)); // 2.5
        assertThat(r.assetTurnover()).isEqualTo(0.05 / 0.25);           // 0.2 次
        assertThat(r.netMargin()).isEqualTo(0.25);
        assertThat(r.roePercent()).isEqualTo(15.0);
        assertThat(r.missingFactors()).isEmpty();
    }

    @DisplayName("资产负债率缺失：权益乘数缺失记入 missingFactors，其余因子仍算")
    @Test
    void givenNullDebt_whenOf_thenMultiplierMissing() {
        var r = DuPontAnalysis.of(0.25, 5.0, null, 15.0, "2026-06-30", null);
        assertThat(r.equityMultiplier()).isNull();
        assertThat(r.assetTurnover()).isEqualTo(0.2);
        assertThat(r.missingFactors()).containsExactly("资产负债率");
    }

    @DisplayName("资产负债率≥100：权益乘数无意义记缺失")
    @Test
    void givenDebtOver100_whenOf_thenMultiplierMissing() {
        var r = DuPontAnalysis.of(0.25, 5.0, 100.0, 3.0, "2026-06-30", null);
        assertThat(r.equityMultiplier()).isNull();
        assertThat(r.missingFactors()).anyMatch(s -> s.contains("权益乘数"));
    }

    @DisplayName("净利率缺失（东财 live 不可用）：周转率随之缺失")
    @Test
    void givenNullNetMargin_whenOf_thenTurnoverMissing() {
        var r = DuPontAnalysis.of(null, 5.0, 60.0, 15.0, "2026-06-30", null);
        assertThat(r.netMargin()).isNull();
        assertThat(r.assetTurnover()).isNull();
        assertThat(r.missingFactors()).contains("净利率").anyMatch(s -> s.contains("总资产周转率"));
    }

    @DisplayName("净利率为 0：除零守卫，周转率缺失")
    @Test
    void givenZeroNetMargin_whenOf_thenTurnoverMissing() {
        var r = DuPontAnalysis.of(0.0, 5.0, 60.0, 0.0, "2026-06-30", null);
        assertThat(r.assetTurnover()).isNull();
        assertThat(r.missingFactors()).anyMatch(s -> s.contains("总资产周转率"));
    }
}

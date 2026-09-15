package com.portfolio.invest.domain.allocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskProfileTest {

    @DisplayName("五档推荐权重各和为100")
    @Test
    void givenAllProfiles_whenSumRecommendedWeights_thenEachEquals100() {
        assertThat(RiskProfile.values()).hasSize(5);
        for (var p : RiskProfile.values()) {
            BigDecimal sum = p.recommendedWeights().values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).as(p.name()).isEqualByComparingTo("100");
        }
    }

    @DisplayName("进取档权重与规格一致")
    @Test
    void givenAggressive_whenReadWeights_thenMatchesSpec() {
        var w = RiskProfile.AGGRESSIVE.recommendedWeights();
        assertThat(w.get(AssetClass.STOCK)).isEqualByComparingTo("80");
        assertThat(w.get(AssetClass.BOND)).isEqualByComparingTo("5");
        assertThat(w.get(AssetClass.GOLD)).isEqualByComparingTo("5");
        assertThat(w.get(AssetClass.CASH)).isEqualByComparingTo("5");
        assertThat(w.get(AssetClass.REITS)).isEqualByComparingTo("5");
    }

    @DisplayName("切点边界落档正确：8/15保守 16/22稳健 23/29平衡 30/35成长 36/40进取")
    @Test
    void givenBoundaryScores_whenFromTotalScore_thenGradedCorrectly() {
        assertThat(RiskProfile.fromTotalScore(8)).isEqualTo(RiskProfile.CONSERVATIVE);
        assertThat(RiskProfile.fromTotalScore(15)).isEqualTo(RiskProfile.CONSERVATIVE);
        assertThat(RiskProfile.fromTotalScore(16)).isEqualTo(RiskProfile.STABLE);
        assertThat(RiskProfile.fromTotalScore(22)).isEqualTo(RiskProfile.STABLE);
        assertThat(RiskProfile.fromTotalScore(23)).isEqualTo(RiskProfile.BALANCED);
        assertThat(RiskProfile.fromTotalScore(29)).isEqualTo(RiskProfile.BALANCED);
        assertThat(RiskProfile.fromTotalScore(30)).isEqualTo(RiskProfile.GROWTH);
        assertThat(RiskProfile.fromTotalScore(35)).isEqualTo(RiskProfile.GROWTH);
        assertThat(RiskProfile.fromTotalScore(36)).isEqualTo(RiskProfile.AGGRESSIVE);
        assertThat(RiskProfile.fromTotalScore(40)).isEqualTo(RiskProfile.AGGRESSIVE);
    }

    @DisplayName("总分越界拒绝")
    @Test
    void givenOutOfRangeScore_whenFromTotalScore_thenReject() {
        assertThatThrownBy(() -> RiskProfile.fromTotalScore(7))
                .isInstanceOf(AllocationException.class)
                .extracting("code").isEqualTo(AllocationErrorCode.INVALID_ANSWERS);
        assertThatThrownBy(() -> RiskProfile.fromTotalScore(41))
                .isInstanceOf(AllocationException.class);
    }
}

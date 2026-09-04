package com.portfolio.invest.domain.allocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class AllocationTemplateTest {
    @DisplayName("四个模板权重和为100")
    @Test
    void givenAllFourTemplates_whenSumWeights_thenEachEquals100() {
        assertThat(AllocationTemplate.values()).hasSize(4);
        for (var t : AllocationTemplate.values()) {
            BigDecimal sum = t.weights().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).isEqualByComparingTo("100");
        }
    }

    @DisplayName("永久组合为四等分")
    @Test
    void givenPermanentPortfolio_whenReadWeights_thenSplitIntoFourEqualParts() {
        var w = AllocationTemplate.PERMANENT_PORTFOLIO.weights();
        assertThat(w.get(AssetClass.STOCK)).isEqualByComparingTo("25");
        assertThat(w.get(AssetClass.BOND)).isEqualByComparingTo("25");
        assertThat(w.get(AssetClass.GOLD)).isEqualByComparingTo("25");
        assertThat(w.get(AssetClass.CASH)).isEqualByComparingTo("25");
    }
}

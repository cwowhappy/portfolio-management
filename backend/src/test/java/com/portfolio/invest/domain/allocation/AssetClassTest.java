package com.portfolio.invest.domain.allocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AssetClassTest {
    @DisplayName("五个资产类别带中文标签")
    @Test
    void givenAssetClassEnum_whenReadLabels_thenFiveCarryChineseLabels() {
        assertThat(AssetClass.values()).hasSize(5);
        assertThat(AssetClass.STOCK.label()).isEqualTo("股票");
        assertThat(AssetClass.BOND.label()).isEqualTo("债券");
        assertThat(AssetClass.GOLD.label()).isEqualTo("黄金");
        assertThat(AssetClass.CASH.label()).isEqualTo("现金");
        assertThat(AssetClass.REITS.label()).isEqualTo("REITs");
    }
}

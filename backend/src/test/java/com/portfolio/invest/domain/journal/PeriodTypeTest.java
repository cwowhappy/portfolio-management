package com.portfolio.invest.domain.journal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PeriodTypeTest {
    @DisplayName("两种期间类型带中文标签")
    @Test
    void twoPeriodTypesCarryChineseLabels() {
        assertThat(PeriodType.values()).hasSize(2);
        assertThat(PeriodType.QUARTERLY.label()).isEqualTo("季度");
        assertThat(PeriodType.ANNUAL.label()).isEqualTo("年度");
    }
}

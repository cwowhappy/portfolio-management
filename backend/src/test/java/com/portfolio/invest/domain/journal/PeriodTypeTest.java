package com.portfolio.invest.domain.journal;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PeriodTypeTest {
    @Test
    void 两种期间类型带中文标签() {
        assertThat(PeriodType.values()).hasSize(2);
        assertThat(PeriodType.QUARTERLY.label()).isEqualTo("季度");
        assertThat(PeriodType.ANNUAL.label()).isEqualTo("年度");
    }
}

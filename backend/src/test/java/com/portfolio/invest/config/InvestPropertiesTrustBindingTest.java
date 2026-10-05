package com.portfolio.invest.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * invest.trust 配置组绑定（MS-29 B2，容差参数组① + 修正参数组⑥，设计规格 §4.6）：
 * 用户拍板默认 0.02/0.05/0.01/0.10 + max-retries=2；部分绑定时缺省分量回退默认（record 无空值）。
 */
class InvestPropertiesTrustBindingTest {

    @DisplayName("未配置时：代码默认值生效（relative=0.02 absolute=0.05 price=0.01 deviation=0.10 max-retries=2）")
    @Test
    void givenNoTrustConfig_whenBind_thenDefaultsApply() {
        InvestProperties props = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("invest", InvestProperties.class);

        var tolerance = props.getTrust().getTolerance();
        assertThat(tolerance.relative()).isEqualByComparingTo("0.02");
        assertThat(tolerance.absolute()).isEqualByComparingTo("0.05");
        assertThat(tolerance.price()).isEqualByComparingTo("0.01");
        assertThat(tolerance.deviation()).isEqualByComparingTo("0.10");
        assertThat(props.getTrust().getCorrection().maxRetries()).isEqualTo(2);
    }

    @DisplayName("全量绑定：invest.trust.tolerance.* 与 invest.trust.correction.max-retries 松命名绑定")
    @Test
    void givenFullTrustConfig_whenBind_thenAllValuesBound() {
        InvestProperties props = new Binder(new MapConfigurationPropertySource(Map.of(
                "invest.trust.tolerance.relative", "0.05",
                "invest.trust.tolerance.absolute", "0.10",
                "invest.trust.tolerance.price", "0.02",
                "invest.trust.tolerance.deviation", "0.20",
                "invest.trust.correction.max-retries", "3")))
                .bindOrCreate("invest", InvestProperties.class);

        var tolerance = props.getTrust().getTolerance();
        assertThat(tolerance.relative()).isEqualByComparingTo(new BigDecimal("0.05"));
        assertThat(tolerance.absolute()).isEqualByComparingTo(new BigDecimal("0.10"));
        assertThat(tolerance.price()).isEqualByComparingTo(new BigDecimal("0.02"));
        assertThat(tolerance.deviation()).isEqualByComparingTo(new BigDecimal("0.20"));
        assertThat(props.getTrust().getCorrection().maxRetries()).isEqualTo(3);
    }

    @DisplayName("部分绑定：仅设 relative，其余分量保持默认（record 构造器空值兜底，不产生 null）")
    @Test
    void givenPartialTrustConfig_whenBind_thenRemainingComponentsFallBackToDefaults() {
        InvestProperties props = new Binder(new MapConfigurationPropertySource(Map.of(
                "invest.trust.tolerance.relative", "0.01")))
                .bindOrCreate("invest", InvestProperties.class);

        var tolerance = props.getTrust().getTolerance();
        assertThat(tolerance.relative()).isEqualByComparingTo("0.01");
        assertThat(tolerance.absolute()).isEqualByComparingTo("0.05");
        assertThat(tolerance.price()).isEqualByComparingTo("0.01");
        assertThat(tolerance.deviation()).isEqualByComparingTo("0.10");
    }
}

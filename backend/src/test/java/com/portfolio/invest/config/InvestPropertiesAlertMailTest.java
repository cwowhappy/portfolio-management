package com.portfolio.invest.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 告警邮件降级收件人绑定（B13）：ALERT_MAIL_TO 逗号分隔 → List&lt;String&gt;；空串 → 空列表（不降级）。 */
class InvestPropertiesAlertMailTest {

    @DisplayName("invest.mail.alert-mail-to 逗号分隔字符串松绑定为 List<String>")
    @Test
    void givenCommaSeparatedValue_whenBind_thenSplitIntoList() {
        InvestProperties props = new Binder(new MapConfigurationPropertySource(Map.of(
                "invest.mail.alert-mail-to", "ops@x.com,dev@x.com")))
                .bindOrCreate("invest", InvestProperties.class);

        assertThat(props.getMail().getAlertMailTo()).containsExactly("ops@x.com", "dev@x.com");
    }

    @DisplayName("ALERT_MAIL_TO 未设置（yml 兜底空串）绑定为空列表")
    @Test
    void givenEmptyString_whenBind_thenEmptyList() {
        InvestProperties props = new Binder(new MapConfigurationPropertySource(Map.of(
                "invest.mail.alert-mail-to", "")))
                .bindOrCreate("invest", InvestProperties.class);

        assertThat(props.getMail().getAlertMailTo()).isEmpty();
    }
}

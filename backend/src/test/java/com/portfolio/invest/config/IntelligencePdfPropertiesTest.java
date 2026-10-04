package com.portfolio.invest.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 情报公告 PDF 白名单配置（B7）：默认可信源 + 配置绑定覆盖。 */
class IntelligencePdfPropertiesTest {

    @DisplayName("默认白名单为巨潮静态站 + 东财 PDF CDN")
    @Test
    void givenNoCustomization_whenGetAllowedHosts_thenDefaultTrustedSources() {
        assertThat(new IntelligencePdfProperties().getAllowedHosts())
                .containsExactly("static.cninfo.com.cn", "pdf.dfcfw.com");
    }

    @DisplayName("invest.intelligence.pdf.allowed-hosts 绑定覆盖默认白名单")
    @Test
    void givenCustomHostsProperty_whenBind_thenAllowedHostsOverridden() {
        Map<String, Object> map = Map.of(
                "invest.intelligence.pdf.allowed-hosts[0]", "example.com",
                "invest.intelligence.pdf.allowed-hosts[1]", "cdn.example.org");

        IntelligencePdfProperties props = new Binder(new MapConfigurationPropertySource(map))
                .bindOrCreate("invest.intelligence.pdf", IntelligencePdfProperties.class);

        assertThat(props.getAllowedHosts()).containsExactly("example.com", "cdn.example.org");
    }
}

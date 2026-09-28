package com.portfolio.invest.infrastructure.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.config.InvestProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * P3-T5 证伪飞书提醒薄壳：文案仅项目名 + 条件名（D15，不含数字）、失败返回 false、
 * 异常吞掉不抛（扫描主流程不被推送失败打断）。
 */
class FeishuResearchFalsifierNotifierTest {

    private final FeishuClient client = mock(FeishuClient.class);
    private FeishuResearchFalsifierNotifier notifier;

    @BeforeEach
    void setUp() {
        InvestProperties props = new InvestProperties();
        props.getIm().setChatId("oc_research");
        notifier = new FeishuResearchFalsifierNotifier(client, props);
    }

    @Test
    @DisplayName("给定命中条件，when推送，then卡片为【证伪提醒】前缀且全文不含数字（D15）")
    void givenConditions_whenNotify_thenCardContainsTitleAndNamesWithoutDigits() {
        when(client.sendCard(any(), any(), any(), anyList())).thenReturn(true);

        boolean ok = notifier.notify(7L, "茅台扩产研究", List.of("价格跌破", "PE 高于"));

        assertThat(ok).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(client).sendCard(eq("oc_research"), eq("【证伪提醒】茅台扩产研究"),
                eq("red"), linesCaptor.capture());
        String text = String.join("\n", linesCaptor.getValue());
        assertThat(text)
                .contains("价格跌破")
                .contains("PE 高于")
                .contains("详情见研究项目页")
                .doesNotContain("13")   // 阈值数字不外发
                .doesNotContain("12.34");
    }

    @Test
    @DisplayName("给定飞书发送失败，when推送，then返回 false 不抛")
    void givenClientFails_whenNotify_thenReturnsFalse() {
        when(client.sendCard(any(), any(), any(), anyList())).thenReturn(false);

        assertThat(notifier.notify(7L, "茅台扩产研究", List.of("价格跌破"))).isFalse();
    }

    @Test
    @DisplayName("给定飞书客户端抛异常，when推送，then内部吞掉记 WARN 不抛（降级保护）")
    void givenClientThrows_whenNotify_thenSwallowed() {
        when(client.sendCard(anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> {
            boolean ok = notifier.notify(7L, "茅台扩产研究", List.of("价格跌破"));
            assertThat(ok).isFalse();
        }).doesNotThrowAnyException();
    }
}

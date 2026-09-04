package com.portfolio.invest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.market.MarketDataErrorCode;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.infrastructure.cache.TtlCache;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/** 健康（liveness）与状态（含行情探活）端点。 */
class HealthControllerTest {

    private MarketDataService market;
    private InvestProperties props;
    private Environment env;
    private AtomicLong now;
    private HealthController controller;

    @BeforeEach
    void setUp() {
        market = mock(MarketDataService.class);
        props = new InvestProperties();
        env = mock(Environment.class);
        now = new AtomicLong(0);
        controller = new HealthController(market, props, env, new TtlCache(100, now::get));
    }

    // ———— /health：纯 liveness ————

    @DisplayName("health只报llm配置不外呼行情")
    @Test
    void givenLlmKeyConfigured_whenGetHealth_thenReportOnlyLlmAndNoMarketCall() {
        when(env.getProperty("DEEPSEEK_API_KEY")).thenReturn("sk-xxx");
        Map<String, Object> body = controller.health();
        assertThat(body.get("status")).isEqualTo("up");
        Map<?, ?> llm = (Map<?, ?>) body.get("llm");
        assertThat(llm.get("provider")).isEqualTo("deepseek");
        assertThat(llm.get("model")).isEqualTo("deepseek-v4-flash");
        assertThat(llm.get("keyConfigured")).isEqualTo(true);
        // liveness 不得包含行情探活，且零外呼
        assertThat(body).doesNotContainKey("market");
        verifyNoInteractions(market);
    }

    @DisplayName("health在key未配置时状态degraded")
    @Test
    void givenLlmKeyNotConfigured_whenGetHealth_thenDegraded() {
        when(env.getProperty("DEEPSEEK_API_KEY")).thenReturn(null);
        Map<String, Object> body = controller.health();
        assertThat(body.get("status")).isEqualTo("degraded");
        Map<?, ?> llm = (Map<?, ?>) body.get("llm");
        assertThat(llm.get("keyConfigured")).isEqualTo(false);
        verifyNoInteractions(market);
    }

    @DisplayName("health在key为空白字符串时视为未配置")
    @Test
    void givenLlmKeyBlank_whenGetHealth_thenTreatAsNotConfigured() {
        when(env.getProperty("DEEPSEEK_API_KEY")).thenReturn("   ");
        Map<?, ?> llm = (Map<?, ?>) controller.health().get("llm");
        assertThat(llm.get("keyConfigured")).isEqualTo(false);
    }

    // ———— /status：完整结构 + 探活缓存 ————

    @DisplayName("status在key已配置且行情可用时状态up")
    @Test
    void givenKeyConfiguredAndMarketAvailable_whenGetStatus_thenUp() {
        when(env.getProperty("DEEPSEEK_API_KEY")).thenReturn("sk-xxx");
        when(market.probeQuoteLatencyMs()).thenReturn(42L);
        Map<String, Object> body = controller.status();
        assertThat(body.get("status")).isEqualTo("up");
        Map<?, ?> m = (Map<?, ?>) body.get("market");
        assertThat(m.get("ok")).isEqualTo(true);
        assertThat(m.get("latencyMs")).isEqualTo(42L);
    }

    @DisplayName("status在key未配置时即使行情可用仍degraded")
    @Test
    void givenKeyNotConfiguredButMarketAvailable_whenGetStatus_thenStillDegraded() {
        when(env.getProperty("DEEPSEEK_API_KEY")).thenReturn(null);
        when(market.probeQuoteLatencyMs()).thenReturn(42L);

        Map<String, Object> body = controller.status();

        assertThat(body.get("status")).isEqualTo("degraded");
        Map<?, ?> m = (Map<?, ?>) body.get("market");
        assertThat(m.get("ok")).isEqualTo(true);
    }

    @DisplayName("status在行情探活失败时标记不可用并附消息")
    @Test
    void givenMarketProbeFails_whenGetStatus_thenMarketUnavailableWithMessage() {
        when(env.getProperty("DEEPSEEK_API_KEY")).thenReturn("sk-xxx");
        when(market.probeQuoteLatencyMs())
                .thenThrow(new MarketDataException(MarketDataErrorCode.UPSTREAM_UNAVAILABLE, "行情源挂了"));
        Map<String, Object> body = controller.status();
        assertThat(body.get("status")).isEqualTo("degraded");
        Map<?, ?> m = (Map<?, ?>) body.get("market");
        assertThat(m.get("ok")).isEqualTo(false);
        assertThat(m.get("message")).isEqualTo("行情源挂了");
    }

    @DisplayName("status连续调用时探活结果命中缓存只外呼一次")
    @Test
    void givenMarketProbeAvailable_whenGetStatusRepeatedly_thenProbeOnlyOnceViaCache() {
        when(env.getProperty("DEEPSEEK_API_KEY")).thenReturn("sk-xxx");
        when(market.probeQuoteLatencyMs()).thenReturn(42L);
        controller.status();
        controller.status();
        verify(market, times(1)).probeQuoteLatencyMs();
    }

    @DisplayName("status探活缓存过期后重新外呼")
    @Test
    void givenProbeCacheExpired_whenGetStatusAgain_thenReProbe() {
        when(env.getProperty("DEEPSEEK_API_KEY")).thenReturn("sk-xxx");
        when(market.probeQuoteLatencyMs()).thenReturn(42L);
        controller.status();
        now.addAndGet(31_000); // 超过 30s TTL
        controller.status();
        verify(market, times(2)).probeQuoteLatencyMs();
    }

    @DisplayName("status探活失败结果同样被缓存")
    @Test
    void givenMarketProbeFails_whenGetStatusRepeatedly_thenFailedResultCached() {
        when(env.getProperty("DEEPSEEK_API_KEY")).thenReturn("sk-xxx");
        when(market.probeQuoteLatencyMs())
                .thenThrow(new MarketDataException(MarketDataErrorCode.UPSTREAM_UNAVAILABLE, "行情源挂了"));
        controller.status();
        controller.status();
        verify(market, times(1)).probeQuoteLatencyMs();
    }
}

package com.portfolio.invest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.market.MarketDataService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/** 健康检查：LLM 配置状态与行情源连通性。 */
class HealthControllerTest {

    private MarketDataService market;
    private InvestProperties props;
    private Environment env;
    private HealthController controller;

    @BeforeEach
    void setUp() {
        market = mock(MarketDataService.class);
        props = new InvestProperties();
        env = mock(Environment.class);
        controller = new HealthController(market, props, env);
    }

    @Test
    void key已配置且行情可用时状态up() {
        when(env.getProperty("DEEPSEEK_API_KEY")).thenReturn("sk-xxx");
        when(market.probeQuoteLatencyMs()).thenReturn(42L);
        Map<String, Object> body = controller.health();
        assertThat(body.get("status")).isEqualTo("up");
        Map<?, ?> llm = (Map<?, ?>) body.get("llm");
        assertThat(llm.get("provider")).isEqualTo("deepseek");
        assertThat(llm.get("model")).isEqualTo("deepseek-v4-flash");
        assertThat(llm.get("keyConfigured")).isEqualTo(true);
        Map<?, ?> m = (Map<?, ?>) body.get("market");
        assertThat(m.get("ok")).isEqualTo(true);
        assertThat(m.get("latencyMs")).isEqualTo(42L);
    }

    @Test
    void key未配置时状态degraded() {
        when(env.getProperty("DEEPSEEK_API_KEY")).thenReturn(null);
        when(market.probeQuoteLatencyMs()).thenReturn(1L);
        Map<String, Object> body = controller.health();
        assertThat(body.get("status")).isEqualTo("degraded");
        Map<?, ?> llm = (Map<?, ?>) body.get("llm");
        assertThat(llm.get("keyConfigured")).isEqualTo(false);
    }

    @Test
    void key为空白字符串视为未配置() {
        when(env.getProperty("DEEPSEEK_API_KEY")).thenReturn("   ");
        when(market.probeQuoteLatencyMs()).thenReturn(1L);
        Map<?, ?> llm = (Map<?, ?>) controller.health().get("llm");
        assertThat(llm.get("keyConfigured")).isEqualTo(false);
    }

    @Test
    void 行情探活失败时标记不可用并附消息() {
        when(env.getProperty("DEEPSEEK_API_KEY")).thenReturn("sk-xxx");
        when(market.probeQuoteLatencyMs())
                .thenThrow(new MarketDataException("UPSTREAM_UNAVAILABLE", "行情源挂了"));
        Map<String, Object> body = controller.health();
        assertThat(body.get("status")).isEqualTo("degraded");
        Map<?, ?> m = (Map<?, ?>) body.get("market");
        assertThat(m.get("ok")).isEqualTo(false);
        assertThat(m.get("message")).isEqualTo("行情源挂了");
    }
}

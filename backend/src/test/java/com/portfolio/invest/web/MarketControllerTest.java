package com.portfolio.invest.web;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.application.market.MarketDataService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.portfolio.invest.domain.market.MarketDataErrorCode;

/** 行情 REST 控制器：HTTP 绑定（path/query）、状态码与领域异常 → 状态码映射。 */
class MarketControllerTest {

    private MarketDataService market;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        market = mock(MarketDataService.class);
        mvc = MockMvcBuilders.standaloneSetup(new MarketController(market))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @DisplayName("quote绑定path参数并返回200")
    @Test
    void quoteBindsPathParamAndReturns200() throws Exception {
        Quote q = new Quote("600519", "贵州茅台", 1415, 15, 1.07, 1410, 1428, 1405.5, 1400,
                2345600, 3.3e9, 21.35, 7.82, "2026-08-18 15:00");
        when(market.quote("600519")).thenReturn(q);
        mvc.perform(get("/api/market/quote/600519"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("600519"))
                .andExpect(jsonPath("$.name").value("贵州茅台"));
    }

    @DisplayName("kline缺省period与limit")
    @Test
    void klineDefaultsPeriodAndLimit() throws Exception {
        when(market.kline(eq("600519"), eq("day"), eq(120))).thenReturn(List.of());
        mvc.perform(get("/api/market/kline/600519"))
                .andExpect(status().isOk());
    }

    @DisplayName("kline非法period映射400")
    @Test
    void klineInvalidPeriodMapsTo400() throws Exception {
        when(market.kline(eq("600519"), eq("foo"), anyInt()))
                .thenThrow(new MarketDataException(MarketDataErrorCode.INVALID_PERIOD, "period 仅支持 day/week/month"));
        mvc.perform(get("/api/market/kline/600519").param("period", "foo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(MarketDataErrorCode.INVALID_PERIOD));
    }

    @DisplayName("news缺省limit与overview透传")
    @Test
    void newsDefaultsLimitAndPassesThroughOverview() throws Exception {
        when(market.news(eq("600519"), eq(10))).thenReturn(List.of());
        mvc.perform(get("/api/market/news/600519"))
                .andExpect(status().isOk());
    }
}

package com.portfolio.invest.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.market.FinancialIndicator;
import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.market.MarketDataErrorCode;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.StockHit;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.infrastructure.security.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 行情 REST 正牌切片（现有 MarketControllerTest 为 standalone 风格，保留不动）。
 * 引入真实 SecurityConfig 验证 /api/market/** 匿名放行；业务由 MarketDataService 打桩。
 */
@WebMvcTest(MarketController.class)
@Import(SecurityConfig.class)
class MarketControllerSliceTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MarketDataService market;

    // SecurityConfig 装配所需依赖（切片内无真实实现）
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private UserDetailsService userDetailsService;
    @MockitoBean
    private RememberMeServices rememberMeServices;
    @MockitoBean
    private PersistentTokenRepository persistentTokenRepository;

    @DisplayName("匿名搜索命中返回200")
    @Test
    void givenAnonymousUserAndSearchHit_whenSearch_thenReturn200() throws Exception {
        when(market.search("茅台")).thenReturn(List.of(
                new StockHit("600519", "贵州茅台", "SH", "上海")));

        mvc.perform(get("/api/market/search").param("q", "茅台"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("600519"))
                .andExpect(jsonPath("$[0].name").value("贵州茅台"));
    }

    @DisplayName("搜索缺少q参数映射400")
    @Test
    void givenMissingQueryParam_whenSearch_thenReturn400() throws Exception {
        mvc.perform(get("/api/market/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @DisplayName("搜索关键词非法映射400")
    @Test
    void givenInvalidKeyword_whenSearch_thenReturn400() throws Exception {
        when(market.search("  ")).thenThrow(
                new MarketDataException(MarketDataErrorCode.INVALID_QUERY, "搜索关键词不能为空"));

        mvc.perform(get("/api/market/search").param("q", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(MarketDataErrorCode.INVALID_QUERY));
    }

    @DisplayName("财务指标正常返回200")
    @Test
    void givenFinancialsAvailable_whenGetFinancials_thenReturn200() throws Exception {
        when(market.financials("600519")).thenReturn(new Financials("600519", "贵州茅台",
                21.35, 7.82, List.of(new FinancialIndicator("2026-06-30", 33.19, 180.5,
                        8.9e10, 4.4e10, 17.5, 91.8))));

        mvc.perform(get("/api/market/financials/600519"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("600519"))
                .andExpect(jsonPath("$.pe").value(21.35))
                .andExpect(jsonPath("$.indicators[0].reportDate").value("2026-06-30"));
    }

    @DisplayName("财务指标上游不可用映射502")
    @Test
    void givenUpstreamUnavailable_whenGetFinancials_thenReturn502() throws Exception {
        when(market.financials("600519")).thenThrow(
                new MarketDataException(MarketDataErrorCode.UPSTREAM_UNAVAILABLE, "上游挂了"));

        mvc.perform(get("/api/market/financials/600519"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(MarketDataErrorCode.UPSTREAM_UNAVAILABLE));
    }
}

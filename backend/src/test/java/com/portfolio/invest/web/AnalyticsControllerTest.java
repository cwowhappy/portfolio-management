package com.portfolio.invest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.application.analytics.AnalyticsApplicationService;
import com.portfolio.invest.application.analytics.AnnualReturnRow;
import com.portfolio.invest.application.analytics.NavSeriesView;
import com.portfolio.invest.application.analytics.OverviewView;
import com.portfolio.invest.application.analytics.TradeStatsView;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * AnalyticsController 四端点契约：有数据 200 带载荷、无数据 204 空体、annual 恒 200 数组
 * （204 语义沿 allocation latestAssessment 先例；载荷断言用 AssertJ 回读反序列化）。
 */
class AnalyticsControllerTest {

    /** 挂 JavaTime 模块（standalone MockMvc 转换器把 LocalDate 写成 [y,M,d] 数组、生产侧为 ISO 串，两种形态均可读回）。 */
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private final AnalyticsApplicationService service = mock(AnalyticsApplicationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AnalyticsController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Authentication auth() {
        var user = User.reconstitute(1L, "u", "p", UserRole.USER, UserStatus.APPROVED, true,
                Instant.now(), Instant.now());
        var principal = new AuthenticatedUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    // ———— overview ————

    @DisplayName("overview有数据返回200及总览载荷")
    @Test
    void givenOverviewData_whenGetOverview_thenReturn200WithPayload() throws Exception {
        OverviewView view = new OverviewView(new BigDecimal("102500.0000"), new BigDecimal("0.0250"),
                new BigDecimal("0.1236"), null, 366L, Map.of(
                "000300", new OverviewView.BenchmarkComparison("000300", "沪深300",
                        new BigDecimal("0.1000"), new BigDecimal("-0.0750"))));
        when(service.overview(1L)).thenReturn(Optional.of(view));

        MvcResult result = mvc.perform(get("/api/analytics/overview").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowDays").value(366))
                .andExpect(jsonPath("$.benchmarks['000300'].indexName").value("沪深300"))
                .andReturn();

        OverviewView body = mapper.readValue(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), OverviewView.class);
        assertThat(body).isEqualTo(view);
        verify(service).overview(1L);
    }

    @DisplayName("overview无数据返回204空体")
    @Test
    void givenNoData_whenGetOverview_thenReturn204EmptyBody() throws Exception {
        when(service.overview(1L)).thenReturn(Optional.empty());

        MvcResult result = mvc.perform(get("/api/analytics/overview").principal(auth()))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEmpty();
        verify(service).overview(1L);
    }

    // ———— nav ————

    @DisplayName("nav有数据返回200及走势载荷")
    @Test
    void givenNavData_whenGetNav_thenReturn200WithPayload() throws Exception {
        NavSeriesView view = new NavSeriesView(LocalDate.parse("2025-01-02"), LocalDate.parse("2026-01-02"),
                List.of(new NavSeriesView.NavPoint(LocalDate.parse("2025-01-02"), new BigDecimal("100000.0000")),
                        new NavSeriesView.NavPoint(LocalDate.parse("2026-01-02"), new BigDecimal("102500.0000"))),
                Map.of("000300", List.of(
                        new NavSeriesView.IndexPoint(LocalDate.parse("2025-01-02"), new BigDecimal("3400.1234")))));
        when(service.nav(1L)).thenReturn(Optional.of(view));

        MvcResult result = mvc.perform(get("/api/analytics/nav").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(2))
                .andReturn();

        NavSeriesView body = mapper.readValue(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), NavSeriesView.class);
        assertThat(body).isEqualTo(view);
        verify(service).nav(1L);
    }

    @DisplayName("nav无数据返回204空体")
    @Test
    void givenNoData_whenGetNav_thenReturn204EmptyBody() throws Exception {
        when(service.nav(1L)).thenReturn(Optional.empty());

        MvcResult result = mvc.perform(get("/api/analytics/nav").principal(auth()))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEmpty();
        verify(service).nav(1L);
    }

    // ———— annual ————

    @DisplayName("annual无流水返回200空数组")
    @Test
    void givenNoData_whenGetAnnual_thenReturn200EmptyArray() throws Exception {
        when(service.annual(1L)).thenReturn(List.of());

        MvcResult result = mvc.perform(get("/api/analytics/annual").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("[]");
        verify(service).annual(1L);
    }

    @DisplayName("annual有数据返回200及年度行")
    @Test
    void givenAnnualRows_whenGetAnnual_thenReturn200WithRows() throws Exception {
        AnnualReturnRow row = new AnnualReturnRow(2025, new BigDecimal("0.0250"),
                Map.of("000300", new BigDecimal("0.1000")),
                Map.of("000300", new BigDecimal("-0.0750")));
        when(service.annual(1L)).thenReturn(List.of(row));

        MvcResult result = mvc.perform(get("/api/analytics/annual").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].year").value(2025))
                .andReturn();

        List<AnnualReturnRow> body = mapper.readValue(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                mapper.getTypeFactory().constructCollectionType(List.class, AnnualReturnRow.class));
        assertThat(body).isEqualTo(List.of(row));
        verify(service).annual(1L);
    }

    // ———— trade-stats ————

    @DisplayName("trade-stats有数据返回200及交易统计载荷")
    @Test
    void givenTradeStats_whenGetTradeStats_thenReturn200WithPayload() throws Exception {
        TradeStatsView view = new TradeStatsView(3, 2, "0.6667", "1200.0000", "300.0000",
                "2.5000", "45", "1800.0000", "300.0000");
        when(service.tradeStats(1L)).thenReturn(Optional.of(view));

        MvcResult result = mvc.perform(get("/api/analytics/trade-stats").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellCount").value(3))
                .andExpect(jsonPath("$.winRate").value("0.6667"))
                .andExpect(jsonPath("$.profitFactor").value("2.5000"))
                .andReturn();

        TradeStatsView body = mapper.readValue(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), TradeStatsView.class);
        assertThat(body).isEqualTo(view);
        verify(service).tradeStats(1L);
    }

    @DisplayName("trade-stats无交易返回204空体")
    @Test
    void givenNoTrades_whenGetTradeStats_thenReturn204EmptyBody() throws Exception {
        when(service.tradeStats(1L)).thenReturn(Optional.empty());

        MvcResult result = mvc.perform(get("/api/analytics/trade-stats").principal(auth()))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEmpty();
        verify(service).tradeStats(1L);
    }
}

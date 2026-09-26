package com.portfolio.invest.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.industry.FundingEventView;
import com.portfolio.invest.application.industry.IndustryApplicationService;
import com.portfolio.invest.application.industry.UnlistedCompanyView;
import com.portfolio.invest.application.industry.UnlistedOverviewView;
import com.portfolio.invest.application.industry.UnlistedResearchApplicationService;
import com.portfolio.invest.domain.industry.IndustryException;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.infrastructure.security.SecurityConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
 * 未上市三公开读端点切片（照 MarketControllerSliceTest 正牌基座）：真实 SecurityConfig
 * 验证 /api/industry/** 匿名放行（公开段零安全改动）；业务由 UnlistedResearchApplicationService
 * 打桩。IndustryApplicationService 同控制器共存，一并打桩。
 */
@WebMvcTest(IndustryController.class)
@Import(SecurityConfig.class)
class IndustryUnlistedReadControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private IndustryApplicationService industryApplicationService;

    @MockitoBean
    private UnlistedResearchApplicationService unlistedService;

    // SecurityConfig 装配所需依赖（切片内无真实实现）
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private UserDetailsService userDetailsService;
    @MockitoBean
    private RememberMeServices rememberMeServices;
    @MockitoBean
    private PersistentTokenRepository persistentTokenRepository;

    private static UnlistedCompanyView companyView(String name, String round, String label) {
        return new UnlistedCompanyView(1L, "801080", name, "半导体设备", round, label,
                LocalDate.of(2026, 6, 15), new BigDecimal("12.50"), "一句话简介", "示例来源",
                Instant.parse("2026-09-01T00:00:00Z"));
    }

    @DisplayName("匿名访问三读端点均 200（公开段放行）")
    @Test
    void givenAnonymous_whenGetThreeUnlistedReads_thenReturn200() throws Exception {
        when(unlistedService.companies("801080")).thenReturn(List.of(companyView("示例华芯科技", "B", "B轮")));
        when(unlistedService.fundingEvents("801080", 24)).thenReturn(List.of());
        when(unlistedService.overview("801080")).thenReturn(new UnlistedOverviewView(3,
                new BigDecimal("26800.00"), 5, 4, List.of(new UnlistedOverviewView.RoundCount("B", 2)),
                UnlistedOverviewView.COVERAGE_NOTE));

        mvc.perform(get("/api/industry/801080/unlisted/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].latestRound").value("B"))
                .andExpect(jsonPath("$[0].latestRoundLabel").value("B轮"))
                .andExpect(jsonPath("$[0].totalFundingYi").value(12.50));
        mvc.perform(get("/api/industry/801080/unlisted/funding-events"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/industry/801080/unlisted/overview"))
                .andExpect(status().isOk());
    }

    @DisplayName("funding-events 默认 months=24 并透出轮次双字段")
    @Test
    void givenEvents_whenGetFundingEventsWithDefaultMonths_thenDelegateAndShape() throws Exception {
        when(unlistedService.fundingEvents("801080", 24)).thenReturn(List.of(new FundingEventView(
                1L, LocalDate.of(2026, 6, 15), "示例华芯科技", "B", "B轮", new BigDecimal("8.50"),
                "深创投、中芯聚源", "801080", "半导体设备", "睿兽分析月报", null,
                Instant.parse("2026-09-01T00:00:00Z"))));

        mvc.perform(get("/api/industry/801080/unlisted/funding-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventDate").value("2026-06-15"))
                .andExpect(jsonPath("$[0].round").value("B"))
                .andExpect(jsonPath("$[0].roundLabel").value("B轮"))
                .andExpect(jsonPath("$[0].amountYi").value(8.50))
                .andExpect(jsonPath("$[0].sourceTitle").value("睿兽分析月报"))
                .andExpect(jsonPath("$[0].sourceUrl").doesNotExist());

        verify(unlistedService).fundingEvents("801080", 24);
    }

    @DisplayName("overview 形状：四指标 + 轮次分布 + 口径字段")
    @Test
    void givenOverview_whenGetOverview_thenShape() throws Exception {
        when(unlistedService.overview("801080")).thenReturn(new UnlistedOverviewView(120,
                new BigDecimal("65000.00"), 5, 4,
                List.of(new UnlistedOverviewView.RoundCount("A", 1),
                        new UnlistedOverviewView.RoundCount("D", 3)),
                UnlistedOverviewView.COVERAGE_NOTE));

        mvc.perform(get("/api/industry/801080/unlisted/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listedCount").value(120))
                .andExpect(jsonPath("$.listedMarketCapYi").value(65000.00))
                .andExpect(jsonPath("$.curatedCount").value(5))
                .andExpect(jsonPath("$.fundingEvents12m").value(4))
                .andExpect(jsonPath("$.roundDistribution[0].round").value("A"))
                .andExpect(jsonPath("$.roundDistribution[0].count").value(1))
                .andExpect(jsonPath("$.roundDistribution[1].round").value("D"))
                .andExpect(jsonPath("$.coverageNote").value("策展名单与月度摘录融资事件，非全量口径"));
    }

    @DisplayName("行业不存在三端点均 404")
    @Test
    void givenUnknownIndustry_whenGetUnlistedReads_thenReturn404() throws Exception {
        when(unlistedService.companies("999999"))
                .thenThrow(new IndustryException("INDUSTRY_NOT_FOUND", "行业不存在: 999999"));
        when(unlistedService.fundingEvents("999999", 24))
                .thenThrow(new IndustryException("INDUSTRY_NOT_FOUND", "行业不存在: 999999"));
        when(unlistedService.overview("999999"))
                .thenThrow(new IndustryException("INDUSTRY_NOT_FOUND", "行业不存在: 999999"));

        mvc.perform(get("/api/industry/999999/unlisted/companies"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INDUSTRY_NOT_FOUND"));
        mvc.perform(get("/api/industry/999999/unlisted/funding-events"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/industry/999999/unlisted/overview"))
                .andExpect(status().isNotFound());
    }

    @DisplayName("months 越界映射 400 INVALID_LIMIT")
    @Test
    void givenMonthsOutOfRange_whenGetFundingEvents_thenReturn400() throws Exception {
        when(unlistedService.fundingEvents("801080", 61))
                .thenThrow(new IndustryException("INDUSTRY_INVALID_LIMIT", "months 须在 1~60 之间"));

        mvc.perform(get("/api/industry/801080/unlisted/funding-events").param("months", "61"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INDUSTRY_INVALID_LIMIT"));
    }
}

package com.portfolio.invest.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.industry.IndustryApplicationService;
import com.portfolio.invest.application.industry.IndustryBoardView;
import com.portfolio.invest.application.industry.IndustryChainApplicationService;
import com.portfolio.invest.application.industry.UnlistedResearchApplicationService;
import com.portfolio.invest.domain.industry.IndustryException;
import com.portfolio.invest.domain.industry.Prosperity;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 行业研究 REST 控制器：HTTP 绑定（path/query 默认值）与状态码。 */
class IndustryControllerTest {

    private final IndustryApplicationService service = mock(IndustryApplicationService.class);
    // MS-10 P2 起控制器共存注入未上市读服务、P3 注入链服务（各自端点切片见对应 *ControllerTest）
    private final UnlistedResearchApplicationService unlistedService =
            mock(UnlistedResearchApplicationService.class);
    private final IndustryChainApplicationService chainService =
            mock(IndustryChainApplicationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new IndustryController(service, unlistedService, chainService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @DisplayName("board返回行业视图与景气原始输入（prosperityInputs 序列化）")
    @Test
    void whenGetBoard_thenReturnBoardViews() throws Exception {
        when(service.board()).thenReturn(List.of(new IndustryBoardView(
                "801780", "银行", new BigDecimal("5.5"), null, null, null, null, null, Prosperity.UP,
                new IndustryBoardView.ProsperityInputs(new BigDecimal("1"), new BigDecimal("10"), 2))));

        mvc.perform(get("/api/industry/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].industryCode").value("801780"))
                .andExpect(jsonPath("$[0].prosperity").value("UP"))
                .andExpect(jsonPath("$[0].prosperityInputs.roeDeltaMedian").value(1))
                .andExpect(jsonPath("$[0].prosperityInputs.revenueYoyMedian").value(10))
                .andExpect(jsonPath("$[0].prosperityInputs.sampleSize").value(2));
    }

    @DisplayName("stocks缺省参数默认total_mv/DESC/1000")
    @Test
    void whenGetStocksWithoutParams_thenUseDefaultSortAndLimit() throws Exception {
        when(service.stocks(any(), any(), any(), eq(1000))).thenReturn(List.of());

        mvc.perform(get("/api/industry/801780/stocks"))
                .andExpect(status().isOk());

        verify(service).stocks("801780", "total_mv", "DESC", 1000);
    }

    @DisplayName("未知行业返回404")
    @Test
    void givenUnknownIndustry_whenGetStocks_thenReturn404() throws Exception {
        when(service.stocks(eq("999999"), any(), any(), eq(1000)))
                .thenThrow(new IndustryException("INDUSTRY_NOT_FOUND", "行业不存在: 999999"));

        mvc.perform(get("/api/industry/999999/stocks"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INDUSTRY_NOT_FOUND"));
    }

    @DisplayName("非法排序字段返回400")
    @Test
    void givenInvalidSort_whenGetStocks_thenReturn400() throws Exception {
        when(service.stocks(eq("801780"), eq("pe"), any(), eq(1000)))
                .thenThrow(new IndustryException("INDUSTRY_INVALID_SORT", "排序参数非法: pe DESC"));

        mvc.perform(get("/api/industry/801780/stocks").param("sortBy", "pe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INDUSTRY_INVALID_SORT"));
    }

    @DisplayName("stocks超上限limit返回400")
    @Test
    void givenLimitAboveMax_whenGetStocks_thenReturn400() throws Exception {
        when(service.stocks(eq("801780"), any(), any(), eq(1001)))
                .thenThrow(new IndustryException("INDUSTRY_INVALID_LIMIT", "limit 须在 1~1000 之间"));

        mvc.perform(get("/api/industry/801780/stocks").param("limit", "1001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INDUSTRY_INVALID_LIMIT"));
    }
}

package com.portfolio.invest.web;

import com.portfolio.invest.application.screening.ScreeningApplicationService;
import com.portfolio.invest.domain.screening.FundScreeningCriteria;
import com.portfolio.invest.domain.screening.FundScreeningResult;
import com.portfolio.invest.domain.screening.ScreeningException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ScreeningControllerTest {

    private final ScreeningApplicationService service = mock(ScreeningApplicationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ScreeningController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @DisplayName("合法条件返回200")
    @Test
    void givenValidConditions_whenScreen_thenReturn200() throws Exception {
        when(service.screen(any())).thenReturn(List.of());
        mvc.perform(get("/api/screening/stocks").param("peTtmMax", "20").param("roeMin", "15"))
                .andExpect(status().isOk());
    }

    @DisplayName("空条件返回400")
    @Test
    void givenEmptyConditions_whenScreen_thenReturn400() throws Exception {
        when(service.screen(any())).thenThrow(new ScreeningException("SCREENING_NO_CONDITION", "至少需要一个筛选条件"));
        mvc.perform(get("/api/screening/stocks"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SCREENING_NO_CONDITION"));
    }

    @DisplayName("基金筛选七参数透传且返回基金 JSON 键名")
    @Test
    void givenFundParams_whenFunds_thenReturn200WithFundKeys() throws Exception {
        when(service.funds(any())).thenReturn(List.of(
                new FundScreeningResult("510300", "沪深300ETF", new BigDecimal("0.6"), new BigDecimal("1200.5"),
                        "沪深300", "宽基", new BigDecimal("0.0318"))));
        mvc.perform(get("/api/screening/funds")
                        .param("feeRateMax", "0.6").param("scaleMin", "100")
                        .param("trackingErrorMax", "0.05").param("category", "宽基")
                        .param("sortBy", "fee_rate").param("sortDirection", "DESC").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fundCode").value("510300"))
                .andExpect(jsonPath("$[0].fundName").value("沪深300ETF"))
                .andExpect(jsonPath("$[0].feeRate").value(0.6))
                .andExpect(jsonPath("$[0].scale").value(1200.5))
                .andExpect(jsonPath("$[0].trackingIndexName").value("沪深300"))
                .andExpect(jsonPath("$[0].category").value("宽基"))
                .andExpect(jsonPath("$[0].trackingError1y").value(0.0318));

        ArgumentCaptor<FundScreeningCriteria> captor = ArgumentCaptor.forClass(FundScreeningCriteria.class);
        verify(service).funds(captor.capture());
        FundScreeningCriteria c = captor.getValue();
        assertThat(c.feeRateMax()).isEqualByComparingTo("0.6");
        assertThat(c.scaleMin()).isEqualByComparingTo("100");
        assertThat(c.trackingErrorMax()).isEqualByComparingTo("0.05");
        assertThat(c.category()).isEqualTo("宽基");
        assertThat(c.sortBy()).isEqualTo("fee_rate");
        assertThat(c.sortDirection().name()).isEqualTo("DESC");
        assertThat(c.limit()).isEqualTo(50);
    }

    @DisplayName("基金空条件返回400")
    @Test
    void givenEmptyFundConditions_whenFunds_thenReturn400() throws Exception {
        when(service.funds(any())).thenThrow(new ScreeningException("SCREENING_NO_CONDITION", "至少需要一个筛选条件"));
        mvc.perform(get("/api/screening/funds"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SCREENING_NO_CONDITION"));
    }

    @DisplayName("基金导出：attachment 文件名 + CSV 中文表头 + TE×100 两位小数 + null 空列")
    @Test
    void givenFundRows_whenExportFunds_thenCsvWithHeaderAndConvertedTe() throws Exception {
        when(service.funds(any())).thenReturn(List.of(
                new FundScreeningResult("510300", "沪深300ETF", new BigDecimal("0.60"), new BigDecimal("1200.50"),
                        "沪深300", "宽基", new BigDecimal("0.0318")),
                new FundScreeningResult("159915", "创业板ETF", null, null, null, "宽基", null)));
        var result = mvc.perform(get("/api/screening/funds/export").param("category", "宽基"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        startsWith("attachment; filename=\"fund-screening-")))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8"))
                .andReturn();
        String text = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(text).contains("代码,名称,费率(%),规模(亿元),跟踪指数,类别,跟踪误差(%,收盘价口径)");
        assertThat(text).contains("# 注：跟踪误差为收盘价口径（含分红/折溢价噪声）与官方净值口径不可直接对比");
        assertThat(text).contains("510300,沪深300ETF,0.60,1200.50,沪深300,宽基,3.18");
        assertThat(text).contains("159915,创业板ETF,,,,宽基,"); // fee/scale/指数/TE null 全空列
    }
}

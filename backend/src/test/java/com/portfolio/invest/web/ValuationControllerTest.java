package com.portfolio.invest.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.valuation.IndustryValuationView;
import com.portfolio.invest.application.valuation.ValuationApplicationService;
import com.portfolio.invest.application.valuation.ValuationHistoryView;
import com.portfolio.invest.application.valuation.ValuationOverviewView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 估值 REST 控制器：HTTP 绑定（path/query）与状态码。 */
class ValuationControllerTest {

    private final ValuationApplicationService service = mock(ValuationApplicationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ValuationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void overview返回200() throws Exception {
        when(service.overview()).thenReturn(new ValuationOverviewView(
                null, null, null, null, null, null, null, List.of(), true));

        mvc.perform(get("/api/valuation/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataAccumulating").value(true));
    }

    @Test
    void industries返回排序结果() throws Exception {
        when(service.industries("pe")).thenReturn(List.of(
                new IndustryValuationView("801780", "银行", new BigDecimal("5.9"), new BigDecimal("0.65"), null, null)));

        mvc.perform(get("/api/valuation/industries").param("sort", "pe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].industryName").value("银行"));
    }

    @Test
    void history返回200() throws Exception {
        when(service.history()).thenReturn(new ValuationHistoryView(List.of(), List.of(), List.of()));

        mvc.perform(get("/api/valuation/history"))
                .andExpect(status().isOk());
    }
}

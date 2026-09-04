package com.portfolio.invest.web;

import com.portfolio.invest.application.screening.ScreeningApplicationService;
import com.portfolio.invest.domain.screening.ScreeningException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    void validConditionsReturn200() throws Exception {
        when(service.screen(any())).thenReturn(List.of());
        mvc.perform(get("/api/screening/stocks").param("peTtmMax", "20").param("roeMin", "15"))
                .andExpect(status().isOk());
    }

    @DisplayName("空条件返回400")
    @Test
    void emptyConditionsReturn400() throws Exception {
        when(service.screen(any())).thenThrow(new ScreeningException("SCREENING_NO_CONDITION", "至少需要一个筛选条件"));
        mvc.perform(get("/api/screening/stocks"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SCREENING_NO_CONDITION"));
    }
}

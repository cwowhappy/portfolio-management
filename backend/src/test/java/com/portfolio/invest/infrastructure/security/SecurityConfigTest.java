package com.portfolio.invest.infrastructure.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.infrastructure.persistence.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class SecurityConfigTest extends IntegrationTestBase {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 匿名访问行情公开() throws Exception {
        mockMvc.perform(get("/api/market/overview"))
                .andExpect(status().is(200));
    }

    @Test
    void 匿名访问agui返回401() throws Exception {
        mockMvc.perform(post("/agui/run").contentType("application/json")
                        .content("{\"messages\":[]}"))
                .andExpect(status().isUnauthorized());
    }
}

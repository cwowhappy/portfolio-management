package com.portfolio.invest.infrastructure.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest extends PostgresTestSupport {

    @Autowired
    MockMvc mockMvc;

    @DisplayName("匿名访问行情公开")
    @Test
    void givenAnonymousUser_whenGetMarketOverview_thenPublic() throws Exception {
        mockMvc.perform(get("/api/market/overview"))
                .andExpect(status().is(200));
    }

    @DisplayName("匿名访问agui返回401")
    @Test
    void givenAnonymousUser_whenPostAguiRun_thenUnauthorized() throws Exception {
        mockMvc.perform(post("/agui/run").contentType("application/json")
                        .content("{\"messages\":[]}"))
                .andExpect(status().isUnauthorized());
    }
}

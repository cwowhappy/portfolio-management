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

    @DisplayName("匿名访问自选返回401（/api/watchlist 不在公开清单）")
    @Test
    void givenAnonymousUser_whenGetWatchlist_thenUnauthorized() throws Exception {
        mockMvc.perform(get("/api/watchlist"))
                .andExpect(status().isUnauthorized());
    }

    @DisplayName("筛选搜索保持公开（/api/screening 前缀）")
    @Test
    void givenAnonymousUser_whenGetScreeningSearch_thenPublicOrBadRequest() throws Exception {
        // search 缺参 q → 400（已过安全层，非 401，证明公开）
        mockMvc.perform(get("/api/screening/stocks/search"))
                .andExpect(status().isBadRequest());
        // export 无条件 → 400（同上，安全层放行；CSV 端点公开）
        mockMvc.perform(get("/api/screening/stocks/export"))
                .andExpect(status().isBadRequest());
    }
}

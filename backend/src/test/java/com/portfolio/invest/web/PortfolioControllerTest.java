package com.portfolio.invest.web;

import com.portfolio.invest.application.portfolio.CreateGroupCommand;
import com.portfolio.invest.application.portfolio.EditTradeCommand;
import com.portfolio.invest.application.portfolio.GroupView;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.application.portfolio.PositionView;
import com.portfolio.invest.application.portfolio.BuyCommand;
import com.portfolio.invest.application.portfolio.RenameGroupCommand;
import com.portfolio.invest.domain.portfolio.GroupType;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PortfolioControllerTest {

    private final PortfolioApplicationService service = mock(PortfolioApplicationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PortfolioController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 构造已认证主体：控制器 currentUserId(auth) 会 cast auth.getPrincipal() 为 AuthenticatedUser。 */
    private org.springframework.security.core.Authentication auth() {
        var user = User.reconstitute(1L, "u", "p", UserRole.USER, UserStatus.APPROVED, true,
                Instant.now(), Instant.now());
        var principal = new AuthenticatedUser(user);
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
    }

    @Test
    void groups返回200() throws Exception {
        when(service.groups(1L)).thenReturn(List.of(
                new GroupView(1L, "华泰", GroupType.ACCOUNT, 0, BigDecimal.ZERO)));

        mvc.perform(get("/api/portfolio/groups").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("华泰"));
    }

    @Test
    void 创建分组返回201() throws Exception {
        when(service.createGroup(eq(1L), any(CreateGroupCommand.class)))
                .thenReturn(new GroupView(5L, "华泰", GroupType.ACCOUNT, 0, BigDecimal.ZERO));

        mvc.perform(post("/api/portfolio/groups").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"华泰\",\"type\":\"ACCOUNT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("华泰"));
    }

    @Test
    void 删除分组返回204() throws Exception {
        mvc.perform(delete("/api/portfolio/groups/7").principal(auth()))
                .andExpect(status().isNoContent());
    }

    @Test
    void 买入返回200() throws Exception {
        when(service.buy(eq(1L), any(BuyCommand.class)))
                .thenReturn(new PositionView(99L, 1L, "600519", "贵州茅台",
                        new BigDecimal("100"), new BigDecimal("1500.05"), new BigDecimal("1500"),
                        new BigDecimal("150000"), new BigDecimal("0"), new BigDecimal("0"),
                        new BigDecimal("0"), new BigDecimal("150005"), new BigDecimal("0")));

        mvc.perform(post("/api/portfolio/positions/buy").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":1,\"stockCode\":\"600519\",\"stockName\":\"贵州茅台\",\"tradeDate\":\"2026-08-27\",\"price\":1500,\"quantity\":100,\"fee\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("600519"));
    }

    @Test
    void 改名分组返回200() throws Exception {
        when(service.renameGroup(eq(1L), eq(7L), any(RenameGroupCommand.class)))
                .thenReturn(new GroupView(7L, "东财", GroupType.ACCOUNT, 0, BigDecimal.ZERO));

        mvc.perform(put("/api/portfolio/groups/7").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"东财\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("东财"));
    }

    @Test
    void 编辑买入交易返回200() throws Exception {
        when(service.editTrade(eq(1L), eq(5L), eq(11L), any(EditTradeCommand.class)))
                .thenReturn(new PositionView(5L, 1L, "600519", "贵州茅台",
                        new BigDecimal("60"), new BigDecimal("110"), new BigDecimal("120"),
                        new BigDecimal("7200"), new BigDecimal("600"), new BigDecimal("10"),
                        new BigDecimal("400"), new BigDecimal("11000"), new BigDecimal("0")));

        mvc.perform(put("/api/portfolio/positions/5/trades/11").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tradeDate\":\"2026-08-27\",\"price\":110,\"quantity\":100,\"fee\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avgCost").value(110));
    }
}

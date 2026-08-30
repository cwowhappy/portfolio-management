package com.portfolio.invest.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.portfolio.AssetAllocationView;
import com.portfolio.invest.application.portfolio.CashDividendCommand;
import com.portfolio.invest.application.portfolio.CashTransactionCommand;
import com.portfolio.invest.application.portfolio.CashTransactionView;
import com.portfolio.invest.application.portfolio.ConcentrationView;
import com.portfolio.invest.application.portfolio.DividendView;
import com.portfolio.invest.application.portfolio.IndustryDistributionView;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.application.portfolio.PositionView;
import com.portfolio.invest.application.portfolio.SellCommand;
import com.portfolio.invest.application.portfolio.StockDividendCommand;
import com.portfolio.invest.application.portfolio.TradeView;
import com.portfolio.invest.domain.portfolio.CashTransactionType;
import com.portfolio.invest.domain.portfolio.DividendType;
import com.portfolio.invest.domain.portfolio.TradeType;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 持仓组合 REST 切片：参数绑定、Bean Validation、出参序列化与状态码。
 * 业务分支由 {@link PortfolioApplicationService} 打桩；安全切片用 Boot 默认链
 * （需认证 + CSRF 开启），故请求携带自定义认证主体与 csrf 令牌。
 */
@WebMvcTest(PortfolioController.class)
class PortfolioControllerSliceTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PortfolioApplicationService service;

    /** 构造已认证主体：控制器 currentUserId(auth) 会 cast auth.getPrincipal() 为 AuthenticatedUser。 */
    private Authentication auth() {
        var user = User.reconstitute(1L, "u", "p", UserRole.USER, UserStatus.APPROVED, true,
                Instant.now(), Instant.now());
        var principal = new AuthenticatedUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private PositionView positionView() {
        return new PositionView(5L, 1L, "600519", "贵州茅台",
                new BigDecimal("60"), new BigDecimal("110"), new BigDecimal("120"),
                new BigDecimal("7200"), new BigDecimal("600"), new BigDecimal("10"),
                new BigDecimal("400"), new BigDecimal("11000"), new BigDecimal("0"));
    }

    @Test
    void 卖出正常绑定返回200() throws Exception {
        when(service.sell(eq(1L), any(SellCommand.class))).thenReturn(positionView());

        mvc.perform(post("/api/portfolio/positions/sell").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionId\":5,\"tradeDate\":\"2026-08-28\",\"price\":120,\"quantity\":50,\"fee\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("600519"));
    }

    @Test
    void 卖出缺少price校验失败返回400() throws Exception {
        mvc.perform(post("/api/portfolio/positions/sell").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionId\":5,\"tradeDate\":\"2026-08-28\",\"quantity\":50,\"fee\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 现金分红正常绑定返回200() throws Exception {
        when(service.addCashDividend(eq(1L), any(CashDividendCommand.class))).thenReturn(positionView());

        mvc.perform(post("/api/portfolio/positions/cash-dividend").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionId\":5,\"exDate\":\"2026-08-28\",\"cashPerShare\":1.2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    void 现金分红每股金额为0校验失败返回400() throws Exception {
        mvc.perform(post("/api/portfolio/positions/cash-dividend").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionId\":5,\"exDate\":\"2026-08-28\",\"cashPerShare\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 送股正常绑定返回200() throws Exception {
        when(service.addStockDividend(eq(1L), any(StockDividendCommand.class))).thenReturn(positionView());

        mvc.perform(post("/api/portfolio/positions/stock-dividend").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionId\":5,\"exDate\":\"2026-08-28\",\"stockRatio\":0.3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("600519"));
    }

    @Test
    void 送股缺少stockRatio校验失败返回400() throws Exception {
        mvc.perform(post("/api/portfolio/positions/stock-dividend").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionId\":5,\"exDate\":\"2026-08-28\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 删除持仓返回204并透传positionId() throws Exception {
        mvc.perform(delete("/api/portfolio/positions/5").with(authentication(auth())).with(csrf()))
                .andExpect(status().isNoContent());

        verify(service).deletePosition(1L, 5L);
    }

    @Test
    void 交易流水返回200() throws Exception {
        when(service.trades(1L, 5L)).thenReturn(List.of(
                new TradeView(11L, TradeType.BUY, LocalDate.parse("2026-08-27"),
                        new BigDecimal("110"), new BigDecimal("100"), new BigDecimal("5"))));

        mvc.perform(get("/api/portfolio/positions/5/trades").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("BUY"))
                .andExpect(jsonPath("$[0].tradeDate").value("2026-08-27"));
    }

    @Test
    void 分红流水返回200() throws Exception {
        when(service.dividends(1L, 5L)).thenReturn(List.of(
                new DividendView(21L, DividendType.CASH, LocalDate.parse("2026-08-28"),
                        new BigDecimal("1.2"), null)));

        mvc.perform(get("/api/portfolio/positions/5/dividends").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("CASH"))
                .andExpect(jsonPath("$[0].cashPerShare").value(1.2));
    }

    @Test
    void 新增现金流水返回201() throws Exception {
        when(service.addCashTransaction(eq(1L), any(CashTransactionCommand.class))).thenReturn(
                new CashTransactionView(31L, 1L, CashTransactionType.DEPOSIT,
                        new BigDecimal("10000"), LocalDate.parse("2026-08-28"), "入金"));

        mvc.perform(post("/api/portfolio/cash-transactions").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":1,\"type\":\"DEPOSIT\",\"amount\":10000,\"txDate\":\"2026-08-28\",\"note\":\"入金\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.note").value("入金"));
    }

    @Test
    void 现金流水金额为0校验失败返回400() throws Exception {
        mvc.perform(post("/api/portfolio/cash-transactions").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":1,\"type\":\"WITHDRAW\",\"amount\":0,\"txDate\":\"2026-08-28\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 查询现金流水返回200() throws Exception {
        when(service.cashTransactions(1L, 1L)).thenReturn(List.of(
                new CashTransactionView(31L, 1L, CashTransactionType.WITHDRAW,
                        new BigDecimal("2000"), LocalDate.parse("2026-08-29"), null)));

        mvc.perform(get("/api/portfolio/cash-transactions").param("groupId", "1")
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("WITHDRAW"));
    }

    @Test
    void 查询现金流水缺少groupId映射400() throws Exception {
        mvc.perform(get("/api/portfolio/cash-transactions").with(authentication(auth())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 资产配置返回200() throws Exception {
        when(service.allocation(1L)).thenReturn(new AssetAllocationView(List.of(
                new AssetAllocationView.Slice("股票", new BigDecimal("7200"), new BigDecimal("41.86")),
                new AssetAllocationView.Slice("现金", new BigDecimal("10000"), new BigDecimal("58.14")))));

        mvc.perform(get("/api/portfolio/allocation").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slices[0].category").value("股票"))
                .andExpect(jsonPath("$.slices[1].category").value("现金"));
    }

    @Test
    void 行业分布返回200() throws Exception {
        when(service.industryDistribution(1L)).thenReturn(new IndustryDistributionView(List.of(
                new IndustryDistributionView.Slice("白酒", new BigDecimal("7200"), new BigDecimal("100")))));

        mvc.perform(get("/api/portfolio/industry-distribution").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slices[0].industryName").value("白酒"));
    }

    @Test
    void 集中度返回200() throws Exception {
        when(service.concentration(1L)).thenReturn(new ConcentrationView(List.of(
                new ConcentrationView.Holding("600519", "贵州茅台",
                        new BigDecimal("7200"), new BigDecimal("100"))),
                new BigDecimal("100")));

        mvc.perform(get("/api/portfolio/concentration").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdings[0].stockCode").value("600519"))
                .andExpect(jsonPath("$.top5Ratio").value(100));
    }
}

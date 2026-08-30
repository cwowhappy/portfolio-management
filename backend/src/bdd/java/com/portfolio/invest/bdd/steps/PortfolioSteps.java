package com.portfolio.invest.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.application.auth.AuthApplicationService;
import com.portfolio.invest.application.auth.RegisterCommand;
import com.portfolio.invest.application.portfolio.BuyCommand;
import com.portfolio.invest.application.portfolio.CashDividendCommand;
import com.portfolio.invest.application.portfolio.CashTransactionCommand;
import com.portfolio.invest.application.portfolio.CreateGroupCommand;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.application.portfolio.PortfolioOverviewView;
import com.portfolio.invest.application.portfolio.SellCommand;
import com.portfolio.invest.domain.market.StockRef;
import com.portfolio.invest.domain.portfolio.CashTransactionType;
import com.portfolio.invest.domain.portfolio.GroupType;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.infrastructure.market.EastmoneyClient;
import io.cucumber.java.zh_cn.假如;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 组合记账步骤：业务计算类场景直调 ApplicationService + 真实 PG（Testcontainers），
 * 行情经 {@code @MockitoBean} 的东财客户端 mock 供给固定价格，数值断言全部手算可验证。
 */
public class PortfolioSteps {

    @Autowired
    AuthApplicationService authService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PortfolioApplicationService portfolioService;

    @Autowired
    EastmoneyClient eastmoneyClient;

    @Autowired
    ScenarioContext ctx;

    private final ObjectMapper mapper = new ObjectMapper();

    @假如("已审核用户 {string}")
    public void 已审核用户(String username) {
        // 业务计算类场景无需走 HTTP 链路：直调注册用例 + 仓库审核，拿到 userId
        authService.register(new RegisterCommand(username, "abc12345"));
        var user = userRepository.findByUsername(username).orElseThrow();
        var approved = userRepository.save(user.approve());
        ctx.setUsername(username);
        ctx.setUserId(approved.id());
    }

    @假如("行情源中 {string} 的最新价为 {bigdecimal} 元，昨收为 {bigdecimal} 元")
    public void 行情源报价(String code, BigDecimal price, BigDecimal prevClose) throws Exception {
        // 重新 stub 即视为「行情源当前状态」：清掉此前交互记录，便于缓存场景精确 verify 调用次数
        Mockito.reset(eastmoneyClient);
        StockRef ref = StockRef.from(code);
        JsonNode json = mapper.readTree("""
                {"data":{"f43":%s,"f44":%s,"f45":%s,"f46":%s,"f47":0,"f48":0,
                "f57":"%s","f58":"测试%s","f60":%s,"f86":0,"f162":5.0,"f167":1.0,"f169":0.1,"f170":0.07}}
                """.formatted(price, price, price, price, ref.code(), ref.code(), prevClose));
        when(eastmoneyClient.quote(ref.secid())).thenReturn(json);
    }

    @当("创建账户分组 {string}")
    public void 创建账户分组(String name) {
        var group = portfolioService.createGroup(ctx.getUserId(), new CreateGroupCommand(name, GroupType.ACCOUNT));
        ctx.setGroupId(group.id());
    }

    @当("向该分组存入现金 {bigdecimal} 元")
    public void 存入现金(BigDecimal amount) {
        portfolioService.addCashTransaction(ctx.getUserId(), new CashTransactionCommand(
                ctx.getGroupId(), CashTransactionType.DEPOSIT, amount, LocalDate.now(), null));
    }

    @当("以 {bigdecimal} 元买入 {int} 股 {string}（代码 {string}，手续费 {bigdecimal} 元）")
    public void 买入(BigDecimal price, int quantity, String stockName, String stockCode, BigDecimal fee) {
        var view = portfolioService.buy(ctx.getUserId(), new BuyCommand(
                ctx.getGroupId(), stockCode, stockName, LocalDate.now(),
                price, BigDecimal.valueOf(quantity), fee));
        ctx.setPositionId(view.id());
    }

    @当("以 {bigdecimal} 元卖出 {int} 股（手续费 {bigdecimal} 元）")
    public void 卖出(BigDecimal price, int quantity, BigDecimal fee) {
        portfolioService.sell(ctx.getUserId(), new SellCommand(
                ctx.getPositionId(), LocalDate.now(), price, BigDecimal.valueOf(quantity), fee));
    }

    @当("每股派发现金分红 {bigdecimal} 元")
    public void 现金分红(BigDecimal cashPerShare) {
        portfolioService.addCashDividend(ctx.getUserId(), new CashDividendCommand(
                ctx.getPositionId(), LocalDate.now(), cashPerShare));
    }

    @那么("持仓数量应为 {bigdecimal} 股，摊薄成本价应为 {bigdecimal} 元")
    public void 持仓断言(BigDecimal quantity, BigDecimal avgCost) {
        var positions = portfolioService.positions(ctx.getUserId(), ctx.getGroupId());
        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).quantity()).isEqualByComparingTo(quantity);
        assertThat(positions.get(0).avgCost()).isEqualByComparingTo(avgCost);
    }

    @那么("该分组现金余额应为 {bigdecimal} 元")
    public void 现金余额断言(BigDecimal expected) {
        var group = portfolioService.groups(ctx.getUserId()).stream()
                .filter(g -> g.id().equals(ctx.getGroupId()))
                .findFirst().orElseThrow();
        assertThat(group.cashBalance()).isEqualByComparingTo(expected);
    }

    @那么("组合总览总资产应为 {bigdecimal} 元，累计分红应为 {bigdecimal} 元，总盈亏应为 {bigdecimal} 元")
    public void 总览断言(BigDecimal totalAssets, BigDecimal totalCashDividend, BigDecimal totalPnl) {
        PortfolioOverviewView overview = portfolioService.overview(ctx.getUserId());
        assertThat(overview.totalAssets()).isEqualByComparingTo(totalAssets);
        assertThat(overview.totalCashDividend()).isEqualByComparingTo(totalCashDividend);
        assertThat(overview.totalPnl()).isEqualByComparingTo(totalPnl);
    }
}

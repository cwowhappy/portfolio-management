package com.portfolio.invest.application.portfolio;

import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.portfolio.CashTransaction;
import com.portfolio.invest.domain.portfolio.CashTransactionType;
import com.portfolio.invest.domain.portfolio.CostMethod;
import com.portfolio.invest.domain.portfolio.Dividend;
import com.portfolio.invest.domain.portfolio.DividendType;
import com.portfolio.invest.domain.portfolio.GroupType;
import com.portfolio.invest.domain.portfolio.HoldingGroup;
import com.portfolio.invest.domain.portfolio.Portfolio;
import com.portfolio.invest.domain.portfolio.PortfolioErrorCode;
import com.portfolio.invest.domain.portfolio.PortfolioException;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Position;
import com.portfolio.invest.domain.portfolio.Trade;
import com.portfolio.invest.domain.portfolio.TradeType;
import com.portfolio.invest.domain.valuation.ShenwanIndustryMapping;
import com.portfolio.invest.domain.valuation.ValuationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioApplicationServiceTest {

    private final PortfolioRepository repo = mock(PortfolioRepository.class);
    private final MarketDataService market = mock(MarketDataService.class);
    private final ValuationRepository valuation = mock(ValuationRepository.class);
    private PortfolioApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PortfolioApplicationService(repo, market, valuation, new PortfolioCreationService(repo));
        when(repo.findPortfolioByUserId(1L)).thenReturn(Optional.of(Portfolio.reconstitute(10L, 1L,
                com.portfolio.invest.domain.portfolio.CostMethod.WEIGHTED_AVG, Instant.now(), Instant.now())));
    }

    @Test
    void 分组列表按组合返回() {
        when(repo.findGroupsByPortfolioId(10L)).thenReturn(List.of(
                HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));

        var groups = service.groups(1L);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).name()).isEqualTo("华泰");
    }

    @Test
    void 删除非空分组抛异常() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.findPositionsByGroupId(1L)).thenReturn(List.of(
                com.portfolio.invest.domain.portfolio.Position.create(10L, 1L, "600519", "贵州茅台", Instant.now())));

        assertThatThrownBy(() -> service.deleteGroup(1L, 1L))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("先清空");
    }

    @Test
    void 创建分组成功() {
        when(repo.saveGroup(any())).thenAnswer(inv -> inv.getArgument(0));
        var view = service.createGroup(1L, new CreateGroupCommand("华泰", GroupType.ACCOUNT));
        assertThat(view.name()).isEqualTo("华泰");
    }

    @Test
    void 访问非本人分组抛NOT_FOUND() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteGroup(1L, 1L))
                .isInstanceOfSatisfying(PortfolioException.class,
                        e -> assertThat(e.code()).isEqualTo(PortfolioErrorCode.NOT_FOUND));
    }

    @Test
    void 买入不存在持仓时新建() {
        when(repo.findPositionByPortfolioIdAndGroupIdAndStockCode(10L, 1L, "600519")).thenReturn(Optional.empty());
        when(repo.findGroupByIdAndPortfolioId(1L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.savePosition(any())).thenAnswer(inv -> {
            Position p = inv.getArgument(0);
            return Position.reconstitute(
                    99L, p.portfolioId(), p.groupId(), p.stockCode(), p.stockName(),
                    p.quantity(), p.costBasis(), p.totalBuyCost(), p.cumulativeCashDividend(),
                    p.realizedPnl(), p.netCashFlow(), p.createdAt(), p.updatedAt());
        });
        when(repo.saveTrade(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.buy(1L, new BuyCommand(1L, "600519", "贵州茅台",
                LocalDate.of(2026, 8, 27), new BigDecimal("1500"),
                new BigDecimal("100"), new BigDecimal("5")));

        assertThat(view.id()).isEqualTo(99L);
        assertThat(view.stockCode()).isEqualTo("600519");
        assertThat(view.quantity()).isEqualByComparingTo("100");
        assertThat(view.avgCost()).isEqualByComparingTo("1500.05");

        ArgumentCaptor<Trade> captor = ArgumentCaptor.forClass(Trade.class);
        verify(repo).saveTrade(captor.capture());
        assertThat(captor.getValue().positionId()).isEqualTo(99L);
    }

    @Test
    void 卖出调用引擎并保存() {
        Position pos = Position.create(10L, 1L, "600519", "贵州茅台", Instant.now())
                .applyBuy(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0"));
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(pos));
        when(repo.savePosition(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.saveTrade(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.sell(1L, new SellCommand(5L, LocalDate.of(2026, 8, 28),
                new BigDecimal("120"), new BigDecimal("40"), new BigDecimal("0")));

        assertThat(view.quantity()).isEqualByComparingTo("60");
        assertThat(view.realizedPnl()).isEqualByComparingTo("800");
    }

    @Test
    void 编辑买入交易后重放全部交易重算成本与已实现收益() {
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(positionWithId(5)));
        when(repo.findTradeById(11L)).thenReturn(Optional.of(
                new Trade(11L, 5L, TradeType.BUY, LocalDate.of(2026, 8, 27),
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0"), Instant.now())));
        when(repo.saveTrade(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findTradesByPositionId(5L)).thenReturn(List.of(
                new Trade(11L, 5L, TradeType.BUY, LocalDate.of(2026, 8, 27),
                        new BigDecimal("110"), new BigDecimal("100"), new BigDecimal("0"), Instant.now()),
                new Trade(12L, 5L, TradeType.SELL, LocalDate.of(2026, 8, 28),
                        new BigDecimal("120"), new BigDecimal("40"), new BigDecimal("0"), Instant.now())));
        when(repo.findDividendsByPositionId(5L)).thenReturn(List.of());
        when(repo.savePosition(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.editTrade(1L, 5L, 11L, new EditTradeCommand(
                LocalDate.of(2026, 8, 27), new BigDecimal("110"),
                new BigDecimal("100"), new BigDecimal("0")));

        // 重放：买入 100@110（成本 11000），卖出 40@120 → 摊薄成本 110、已实现 400
        assertThat(view.avgCost()).isEqualByComparingTo("110");
        assertThat(view.realizedPnl()).isEqualByComparingTo("400");
        assertThat(view.quantity()).isEqualByComparingTo("60");
    }

    @Test
    void 编辑卖出交易被拒绝() {
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(positionWithId(5)));
        when(repo.findTradeById(12L)).thenReturn(Optional.of(
                new Trade(12L, 5L, TradeType.SELL, LocalDate.of(2026, 8, 28),
                        new BigDecimal("120"), new BigDecimal("40"), new BigDecimal("0"), Instant.now())));

        assertThatThrownBy(() -> service.editTrade(1L, 5L, 12L, new EditTradeCommand(
                LocalDate.of(2026, 8, 28), new BigDecimal("120"),
                new BigDecimal("40"), new BigDecimal("0"))))
                .isInstanceOfSatisfying(PortfolioException.class,
                        e -> assertThat(e.code()).isEqualTo(PortfolioErrorCode.NOT_FOUND));
    }

    @Test
    void 总览计算总资产与总盈亏() {
        Position pos = Position.create(10L, 1L, "600519", "贵州茅台", Instant.now())
                .applyBuy(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0"));
        when(repo.findPositionsByPortfolioId(10L)).thenReturn(List.of(pos));
        when(repo.findGroupsByPortfolioId(10L)).thenReturn(List.of(
                HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.findCashTransactionsByGroupId(1L)).thenReturn(List.of());
        when(market.quote("600519")).thenReturn(new Quote(
                "600519", "贵州茅台", 120, 0, 0, 0, 0, 0, 100, 0, 0, null, null, ""));

        var view = service.overview(1L);

        assertThat(view.totalAssets()).isEqualByComparingTo("12000"); // 120*100 + 现金 0
        assertThat(view.totalPnl()).isEqualByComparingTo("2000");     // 浮动 (120-100)*100 + 已实现 0
    }

    @Test
    void 按非本人分组查持仓抛NOT_FOUND() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.positions(1L, 1L))
                .isInstanceOfSatisfying(PortfolioException.class,
                        e -> assertThat(e.code()).isEqualTo(PortfolioErrorCode.NOT_FOUND));
    }

    @Test
    void 编辑其他持仓名下的交易被拒绝() {
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(positionWithId(5)));
        // 交易属于持仓 6，不属于持仓 5：不泄露存在性，一律 NOT_FOUND
        when(repo.findTradeById(11L)).thenReturn(Optional.of(
                new Trade(11L, 6L, TradeType.BUY, LocalDate.of(2026, 8, 27),
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0"), Instant.now())));

        assertThatThrownBy(() -> service.editTrade(1L, 5L, 11L, new EditTradeCommand(
                LocalDate.of(2026, 8, 27), new BigDecimal("110"),
                new BigDecimal("100"), new BigDecimal("0"))))
                .isInstanceOfSatisfying(PortfolioException.class,
                        e -> assertThat(e.code()).isEqualTo(PortfolioErrorCode.NOT_FOUND));
    }

    @Test
    void 编辑交易后重放含现金分红按除息日顺序重算() {
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(positionWithId(5)));
        when(repo.findTradeById(11L)).thenReturn(Optional.of(
                new Trade(11L, 5L, TradeType.BUY, LocalDate.of(2026, 8, 27),
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0"), Instant.now())));
        when(repo.saveTrade(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findTradesByPositionId(5L)).thenReturn(List.of(
                new Trade(11L, 5L, TradeType.BUY, LocalDate.of(2026, 8, 27),
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0"), Instant.now())));
        when(repo.findDividendsByPositionId(5L)).thenReturn(List.of(
                new Dividend(1L, 5L, DividendType.CASH, LocalDate.of(2026, 8, 28),
                        new BigDecimal("1.5"), null, Instant.now())));
        when(repo.savePosition(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.editTrade(1L, 5L, 11L, new EditTradeCommand(
                LocalDate.of(2026, 8, 27), new BigDecimal("100"),
                new BigDecimal("100"), new BigDecimal("0")));

        // 重放：先买入 100@100（成本 10000），交易日后再现金分红 1.5*100=150 → 成本 9850
        assertThat(view.quantity()).isEqualByComparingTo("100");
        assertThat(view.avgCost()).isEqualByComparingTo("98.50");
        assertThat(view.cumulativeCashDividend()).isEqualByComparingTo("150");
    }

    @Test
    void 编辑交易后重放含送股且除息日早于后续交易时先分红() {
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(positionWithId(5)));
        when(repo.findTradeById(11L)).thenReturn(Optional.of(
                new Trade(11L, 5L, TradeType.BUY, LocalDate.of(2026, 8, 25),
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0"), Instant.now())));
        when(repo.saveTrade(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findTradesByPositionId(5L)).thenReturn(List.of(
                new Trade(11L, 5L, TradeType.BUY, LocalDate.of(2026, 8, 25),
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0"), Instant.now()),
                new Trade(12L, 5L, TradeType.BUY, LocalDate.of(2026, 8, 27),
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0"), Instant.now())));
        when(repo.findDividendsByPositionId(5L)).thenReturn(List.of(
                new Dividend(1L, 5L, DividendType.STOCK, LocalDate.of(2026, 8, 26),
                        null, new BigDecimal("0.5"), Instant.now())));
        when(repo.savePosition(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.editTrade(1L, 5L, 11L, new EditTradeCommand(
                LocalDate.of(2026, 8, 25), new BigDecimal("100"),
                new BigDecimal("100"), new BigDecimal("0")));

        // 重放：买 100@100 → 8-26 除息日早于 8-27 第二笔交易，先送股 100*1.5=150 → 再买 100@100
        // 数量 250，总成本 20000 → 均价 80
        assertThat(view.quantity()).isEqualByComparingTo("250");
        assertThat(view.avgCost()).isEqualByComparingTo("80");
    }

    @Test
    void 配置中行情缺失时忽略该持仓权益() {
        when(repo.findPositionsByPortfolioId(10L)).thenReturn(List.of(positionWithId(1)));
        when(repo.findGroupsByPortfolioId(10L)).thenReturn(List.of());
        when(market.quote("600519")).thenThrow(new RuntimeException("无行情"));

        var view = service.allocation(1L);

        assertThat(view.slices().get(0).category()).isEqualTo("权益");
        assertThat(view.slices().get(0).marketValue()).isEqualByComparingTo("0");
        assertThat(view.slices().get(1).marketValue()).isEqualByComparingTo("0");
    }

    private Position positionWithId(long id) {
        var base = Position.create(10L, 1L, "600519", "贵州茅台", Instant.now())
                .applyBuy(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0"));
        return Position.reconstitute(id, base.portfolioId(), base.groupId(), base.stockCode(), base.stockName(),
                base.quantity(), base.costBasis(), base.totalBuyCost(), base.cumulativeCashDividend(),
                base.realizedPnl(), base.netCashFlow(), base.createdAt(), base.updatedAt());
    }

    @Test
    void 缺省时自动创建组合() {
        Portfolio created = Portfolio.reconstitute(20L, 2L, CostMethod.WEIGHTED_AVG,
                Instant.now(), Instant.now());
        when(repo.findPortfolioByUserId(2L)).thenReturn(Optional.empty(), Optional.of(created));
        when(repo.saveGroup(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.createGroup(2L, new CreateGroupCommand("华泰", GroupType.ACCOUNT));

        assertThat(view.name()).isEqualTo("华泰");
        verify(repo).insertPortfolioIfAbsent(2L);
    }

    @Test
    void 删除空分组成功() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.findPositionsByGroupId(1L)).thenReturn(List.of());

        service.deleteGroup(1L, 1L);

        verify(repo).deleteGroup(1L);
    }

    @Test
    void 改名分组成功() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.saveGroup(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findPositionsByGroupId(1L)).thenReturn(List.of());
        when(repo.findCashTransactionsByGroupId(1L)).thenReturn(List.of());

        var view = service.renameGroup(1L, 1L, new RenameGroupCommand("东财"));

        assertThat(view.name()).isEqualTo("东财");
        assertThat(view.id()).isEqualTo(1L);

        ArgumentCaptor<HoldingGroup> captor = ArgumentCaptor.forClass(HoldingGroup.class);
        verify(repo).saveGroup(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("东财");
        assertThat(captor.getValue().type()).isEqualTo(GroupType.ACCOUNT);
    }

    @Test
    void 改名非本人分组抛NOT_FOUND() {
        when(repo.findGroupByIdAndPortfolioId(99L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.renameGroup(1L, 99L, new RenameGroupCommand("东财")))
                .isInstanceOfSatisfying(PortfolioException.class,
                        e -> assertThat(e.code()).isEqualTo(PortfolioErrorCode.NOT_FOUND));
    }

    @Test
    void 买入已有持仓累加() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.findPositionByPortfolioIdAndGroupIdAndStockCode(10L, 1L, "600519"))
                .thenReturn(Optional.of(positionWithId(5)));
        when(repo.savePosition(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.saveTrade(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.buy(1L, new BuyCommand(1L, "600519", "贵州茅台",
                LocalDate.of(2026, 8, 27), new BigDecimal("110"), new BigDecimal("50"), new BigDecimal("0")));

        assertThat(view.id()).isEqualTo(5L);
        assertThat(view.quantity()).isEqualByComparingTo("150");
    }

    @Test
    void 现金转入转出与分组列表现金余额() {
        when(repo.findGroupsByPortfolioId(10L)).thenReturn(List.of(
                HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.findPositionsByGroupId(1L)).thenReturn(List.of(positionWithId(5)));
        when(repo.findCashTransactionsByGroupId(1L)).thenReturn(List.of(
                new CashTransaction(1L, 1L, CashTransactionType.DEPOSIT,
                        new BigDecimal("20000"), LocalDate.of(2026, 8, 27), "转入", Instant.now()),
                new CashTransaction(2L, 1L, CashTransactionType.WITHDRAW,
                        new BigDecimal("5000"), LocalDate.of(2026, 8, 28), "转出", Instant.now())));

        var groups = service.groups(1L);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).positionCount()).isEqualTo(1);
        assertThat(groups.get(0).cashBalance()).isEqualByComparingTo("5000");
    }

    @Test
    void 现金转入保存并查询() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.saveCashTransaction(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findCashTransactionsByGroupId(1L)).thenReturn(List.of(
                new CashTransaction(9L, 1L, CashTransactionType.DEPOSIT,
                        new BigDecimal("10000"), LocalDate.of(2026, 8, 27), "转入", Instant.now())));

        var view = service.addCashTransaction(1L, new CashTransactionCommand(1L, CashTransactionType.DEPOSIT,
                new BigDecimal("10000"), LocalDate.of(2026, 8, 27), "转入"));

        assertThat(view.type()).isEqualTo(CashTransactionType.DEPOSIT);
        assertThat(view.amount()).isEqualByComparingTo("10000");
        assertThat(service.cashTransactions(1L, 1L)).hasSize(1);
    }

    @Test
    void 现金转出保存() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.saveCashTransaction(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.addCashTransaction(1L, new CashTransactionCommand(1L, CashTransactionType.WITHDRAW,
                new BigDecimal("5000"), LocalDate.of(2026, 8, 28), "转出"));

        assertThat(view.type()).isEqualTo(CashTransactionType.WITHDRAW);
    }

    @Test
    void 现金分红更新持仓并保存() {
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(positionWithId(5)));
        when(repo.savePosition(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.saveDividend(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findTradesByPositionId(5L)).thenReturn(List.of(
                new Trade(11L, 5L, TradeType.BUY, LocalDate.of(2026, 8, 27),
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0"), Instant.now())));
        when(repo.findDividendsByPositionId(5L)).thenReturn(List.of(
                new Dividend(1L, 5L, DividendType.CASH, LocalDate.of(2026, 8, 28),
                        new BigDecimal("1.5"), null, Instant.now())));

        var view = service.addCashDividend(1L, new CashDividendCommand(5L,
                LocalDate.of(2026, 8, 28), new BigDecimal("1.5")));

        assertThat(view.quantity()).isEqualByComparingTo("100");
        assertThat(view.avgCost()).isEqualByComparingTo("98.50");
        assertThat(view.cumulativeCashDividend()).isEqualByComparingTo("150");

        ArgumentCaptor<Dividend> captor = ArgumentCaptor.forClass(Dividend.class);
        verify(repo).saveDividend(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(DividendType.CASH);
        assertThat(captor.getValue().positionId()).isEqualTo(5L);
    }

    @Test
    void 送股更新持仓并保存() {
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(positionWithId(5)));
        when(repo.savePosition(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.saveDividend(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findTradesByPositionId(5L)).thenReturn(List.of(
                new Trade(11L, 5L, TradeType.BUY, LocalDate.of(2026, 8, 27),
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0"), Instant.now())));
        when(repo.findDividendsByPositionId(5L)).thenReturn(List.of(
                new Dividend(1L, 5L, DividendType.STOCK, LocalDate.of(2026, 8, 28),
                        null, new BigDecimal("0.5"), Instant.now())));

        var view = service.addStockDividend(1L, new StockDividendCommand(5L,
                LocalDate.of(2026, 8, 28), new BigDecimal("0.5")));

        assertThat(view.quantity()).isEqualByComparingTo("150");
        assertThat(view.avgCost()).isEqualByComparingTo("66.67");

        ArgumentCaptor<Dividend> captor = ArgumentCaptor.forClass(Dividend.class);
        verify(repo).saveDividend(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(DividendType.STOCK);
    }

    @Test
    void 补录历史现金分红按除息日当时数量且editTrade重放确定性一致() {
        // 持仓已含两笔买入：08-27 买 100@100、08-29 买 50@100；补录 08-28（两笔买入之间）除息的现金分红。
        Position pos = Position.reconstitute(5L, 10L, 1L, "600519", "贵州茅台",
                new BigDecimal("150"), new BigDecimal("15000"), new BigDecimal("15000"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-15000"),
                Instant.now(), Instant.now());
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(pos));
        when(repo.saveDividend(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.savePosition(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findTradesByPositionId(5L)).thenReturn(List.of(
                new Trade(11L, 5L, TradeType.BUY, LocalDate.of(2026, 8, 27),
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0"), Instant.now()),
                new Trade(12L, 5L, TradeType.BUY, LocalDate.of(2026, 8, 29),
                        new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("0"), Instant.now())));
        when(repo.findDividendsByPositionId(5L)).thenReturn(List.of(
                new Dividend(1L, 5L, DividendType.CASH, LocalDate.of(2026, 8, 28),
                        new BigDecimal("1"), null, Instant.now())));

        var view = service.addCashDividend(1L, new CashDividendCommand(5L,
                LocalDate.of(2026, 8, 28), new BigDecimal("1")));

        // 修复前：按「当前数量 150」直接 apply → 累计分红 150；修复后：按除息日当时数量 100 → 100
        assertThat(view.quantity()).isEqualByComparingTo("150");
        assertThat(view.cumulativeCashDividend()).isEqualByComparingTo("100");

        // editTrade 走同一 replay → 账本数字由事件流确定性导出，不会把 100 静默改写回/到别处
        when(repo.findTradeById(11L)).thenReturn(Optional.of(
                new Trade(11L, 5L, TradeType.BUY, LocalDate.of(2026, 8, 27),
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0"), Instant.now())));
        when(repo.saveTrade(any())).thenAnswer(inv -> inv.getArgument(0));
        var replayed = service.editTrade(1L, 5L, 11L, new EditTradeCommand(
                LocalDate.of(2026, 8, 27), new BigDecimal("100"),
                new BigDecimal("100"), new BigDecimal("0")));

        assertThat(replayed.cumulativeCashDividend()).isEqualByComparingTo(view.cumulativeCashDividend());
        assertThat(replayed.quantity()).isEqualByComparingTo("150");
    }

    @Test
    void 删除持仓() {
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(positionWithId(5)));

        service.deletePosition(1L, 5L);

        verify(repo).deletePosition(5L);
    }

    @Test
    void 查询交易流水() {
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(positionWithId(5)));
        when(repo.findTradesByPositionId(5L)).thenReturn(List.of(
                new Trade(1L, 5L, TradeType.BUY, LocalDate.of(2026, 8, 27),
                        new BigDecimal("1500"), new BigDecimal("100"), new BigDecimal("5"), Instant.now())));

        var trades = service.trades(1L, 5L);

        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).type()).isEqualTo(TradeType.BUY);
        assertThat(trades.get(0).price()).isEqualByComparingTo("1500");
    }

    @Test
    void 查询分红流水() {
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(positionWithId(5)));
        when(repo.findDividendsByPositionId(5L)).thenReturn(List.of(
                new Dividend(1L, 5L, DividendType.CASH, LocalDate.of(2026, 8, 28),
                        new BigDecimal("1.5"), null, Instant.now())));

        var dividends = service.dividends(1L, 5L);

        assertThat(dividends).hasSize(1);
        assertThat(dividends.get(0).type()).isEqualTo(DividendType.CASH);
    }

    @Test
    void 查不存在的持仓抛NOT_FOUND() {
        when(repo.findPositionByIdAndPortfolioId(999L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.trades(1L, 999L))
                .isInstanceOfSatisfying(PortfolioException.class,
                        e -> assertThat(e.code()).isEqualTo(PortfolioErrorCode.NOT_FOUND));
    }

    @Test
    void 按null组查全部持仓() {
        when(repo.findPositionsByPortfolioId(10L)).thenReturn(List.of(positionWithId(5)));
        when(market.quote("600519")).thenReturn(new Quote(
                "600519", "贵州茅台", 120, 0, 0, 0, 0, 0, 100, 0, 0, null, null, ""));

        var positions = service.positions(1L, null);

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).id()).isEqualTo(5L);
        assertThat(positions.get(0).marketValue()).isEqualByComparingTo("12000");
    }

    @Test
    void 按本人组查持仓() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.findPositionsByGroupId(1L)).thenReturn(List.of(positionWithId(5)));
        when(market.quote("600519")).thenReturn(new Quote(
                "600519", "贵州茅台", 120, 0, 0, 0, 0, 0, 100, 0, 0, null, null, ""));

        var positions = service.positions(1L, 1L);

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).groupId()).isEqualTo(1L);
    }

    @Test
    void 总览现价缺失仅计入已实现盈亏() {
        Position pos = Position.reconstitute(1L, 10L, 1L, "600519", "贵州茅台",
                new BigDecimal("100"), new BigDecimal("10000"), new BigDecimal("10000"),
                BigDecimal.ZERO, new BigDecimal("500"), new BigDecimal("-10000"),
                Instant.now(), Instant.now());
        when(repo.findPositionsByPortfolioId(10L)).thenReturn(List.of(pos));
        when(repo.findGroupsByPortfolioId(10L)).thenReturn(List.of());
        when(market.quote("600519")).thenThrow(new RuntimeException("行情不可用"));

        var view = service.overview(1L);

        assertThat(view.totalAssets()).isEqualByComparingTo("0");
        assertThat(view.totalCost()).isEqualByComparingTo("10000");
        assertThat(view.totalPnl()).isEqualByComparingTo("500");
        assertThat(view.todayPnl()).isEqualByComparingTo("0");
    }

    @Test
    void 总览累计现金分红() {
        Position pos = Position.reconstitute(1L, 10L, 1L, "600519", "贵州茅台",
                new BigDecimal("100"), new BigDecimal("9850"), new BigDecimal("10000"),
                new BigDecimal("150"), BigDecimal.ZERO, new BigDecimal("-10000"),
                Instant.now(), Instant.now());
        when(repo.findPositionsByPortfolioId(10L)).thenReturn(List.of(pos));
        when(repo.findGroupsByPortfolioId(10L)).thenReturn(List.of());
        when(market.quote("600519")).thenReturn(new Quote(
                "600519", "贵州茅台", 120, 0, 0, 0, 0, 0, 100, 0, 0, null, null, ""));

        var view = service.overview(1L);

        assertThat(view.totalCashDividend()).isEqualByComparingTo("150");
    }

    @Test
    void 总览忽略非账户分组() {
        Position pos = Position.reconstitute(1L, 10L, 1L, "600519", "贵州茅台",
                new BigDecimal("100"), new BigDecimal("10000"), new BigDecimal("10000"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-10000"),
                Instant.now(), Instant.now());
        when(repo.findPositionsByPortfolioId(10L)).thenReturn(List.of(pos));
        when(repo.findGroupsByPortfolioId(10L)).thenReturn(List.of(
                HoldingGroup.reconstitute(2L, 10L, "观察", GroupType.TAG, Instant.now())));
        when(market.quote("600519")).thenReturn(new Quote(
                "600519", "贵州茅台", 120, 0, 0, 0, 0, 0, 100, 0, 0, null, null, ""));

        var view = service.overview(1L);

        assertThat(view.totalAssets()).isEqualByComparingTo("12000");
        assertThat(view.cashTotal()).isEqualByComparingTo("0");
        assertThat(view.groupCount()).isEqualTo(1);
    }

    @Test
    void 配置权益与现金占比() {
        when(repo.findPositionsByPortfolioId(10L)).thenReturn(List.of(positionWithId(1)));
        when(repo.findGroupsByPortfolioId(10L)).thenReturn(List.of(
                HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now()),
                HoldingGroup.reconstitute(2L, 10L, "观察", GroupType.TAG, Instant.now())));
        when(repo.findCashTransactionsByGroupId(1L)).thenReturn(List.of(
                new CashTransaction(1L, 1L, CashTransactionType.DEPOSIT,
                        new BigDecimal("5000"), LocalDate.of(2026, 8, 27), "转入", Instant.now())));
        when(market.quote("600519")).thenReturn(new Quote(
                "600519", "贵州茅台", 120, 0, 0, 0, 0, 0, 100, 0, 0, null, null, ""));

        var view = service.allocation(1L);

        assertThat(view.slices()).hasSize(2);
        assertThat(view.slices().get(0).category()).isEqualTo("权益");
        assertThat(view.slices().get(0).marketValue()).isEqualByComparingTo("12000");
        assertThat(view.slices().get(0).ratio()).isEqualByComparingTo("70.59");
        assertThat(view.slices().get(1).category()).isEqualTo("现金");
        assertThat(view.slices().get(1).marketValue()).isEqualByComparingTo("5000");
        assertThat(view.slices().get(1).ratio()).isEqualByComparingTo("29.41");
    }

    @Test
    void 空组合配置占比为零() {
        when(repo.findPositionsByPortfolioId(10L)).thenReturn(List.of());
        when(repo.findGroupsByPortfolioId(10L)).thenReturn(List.of());

        var view = service.allocation(1L);

        assertThat(view.slices()).hasSize(2);
        assertThat(view.slices().get(0).ratio()).isEqualByComparingTo("0");
        assertThat(view.slices().get(1).ratio()).isEqualByComparingTo("0");
    }

    @Test
    void 行业分布聚合并排除无映射与无行情() {
        when(repo.findPositionsByPortfolioId(10L)).thenReturn(List.of(
                Position.reconstitute(1L, 10L, 1L, "600519", "贵州茅台",
                        new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, Instant.now(), Instant.now()),
                Position.reconstitute(2L, 10L, 1L, "000858", "五粮液",
                        new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, Instant.now(), Instant.now()),
                Position.reconstitute(3L, 10L, 1L, "601318", "中国平安",
                        new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, Instant.now(), Instant.now())));
        when(valuation.findAllIndustryMappings()).thenReturn(List.of(
                new ShenwanIndustryMapping("600519", "贵州茅台", "801120", "食品饮料"),
                new ShenwanIndustryMapping("601318", "中国平安", "801780", "非银金融")));
        when(market.quote("600519")).thenReturn(new Quote(
                "600519", "贵州茅台", 120, 0, 0, 0, 0, 0, 100, 0, 0, null, null, ""));
        when(market.quote("601318")).thenThrow(new RuntimeException("无行情"));

        var view = service.industryDistribution(1L);

        assertThat(view.slices()).hasSize(1);
        assertThat(view.slices().get(0).industryName()).isEqualTo("食品饮料");
        assertThat(view.slices().get(0).marketValue()).isEqualByComparingTo("12000");
        assertThat(view.slices().get(0).ratio()).isEqualByComparingTo("100");
    }

    @Test
    void 集中度前五与占比() {
        when(repo.findPositionsByPortfolioId(10L)).thenReturn(List.of(
                Position.reconstitute(1L, 10L, 1L, "600519", "贵州茅台",
                        new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, Instant.now(), Instant.now()),
                Position.reconstitute(2L, 10L, 1L, "000858", "五粮液",
                        new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, Instant.now(), Instant.now()),
                Position.reconstitute(3L, 10L, 1L, "601318", "中国平安",
                        new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, Instant.now(), Instant.now())));
        when(market.quote("600519")).thenReturn(new Quote(
                "600519", "贵州茅台", 120, 0, 0, 0, 0, 0, 100, 0, 0, null, null, ""));
        when(market.quote("000858")).thenReturn(new Quote(
                "000858", "五粮液", 30, 0, 0, 0, 0, 0, 100, 0, 0, null, null, ""));
        when(market.quote("601318")).thenThrow(new RuntimeException("无行情"));

        var view = service.concentration(1L);

        assertThat(view.holdings()).hasSize(2);
        assertThat(view.holdings().get(0).stockCode()).isEqualTo("600519");
        assertThat(view.holdings().get(0).ratio()).isEqualByComparingTo("80");
        assertThat(view.holdings().get(1).stockCode()).isEqualTo("000858");
        assertThat(view.holdings().get(1).ratio()).isEqualByComparingTo("20");
        assertThat(view.top5Ratio()).isEqualByComparingTo("100");
    }
}

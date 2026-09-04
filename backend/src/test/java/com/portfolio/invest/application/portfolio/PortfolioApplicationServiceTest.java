package com.portfolio.invest.application.portfolio;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.market.MarketDataErrorCode;
import com.portfolio.invest.domain.market.MarketDataException;
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
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.anyList;
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
        // 批量行情：按各 code 委托逐只 stub（与 OrchestratingMarketDataService.quoteBatch 语义一致：单只失败跳过）
        when(market.quoteBatch(anyList())).thenAnswer(inv -> {
            java.util.List<String> codes = inv.getArgument(0);
            java.util.Map<String, Quote> m = new java.util.LinkedHashMap<>();
            for (String code : codes) {
                try {
                    Quote q = market.quote(code);
                    if (q != null) {
                        m.put(code, q);
                    }
                } catch (RuntimeException ignored) {
                    // 单只失败跳过
                }
            }
            return m;
        });
    }

    @DisplayName("分组列表按组合返回")
    @Test
    void givenPortfolioHasGroups_whenListGroups_thenReturnGroups() {
        when(repo.findGroupsByPortfolioId(10L)).thenReturn(List.of(
                HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));

        var groups = service.groups(1L);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).name()).isEqualTo("华泰");
    }

    @DisplayName("删除非空分组抛异常")
    @Test
    void givenNonEmptyGroup_whenDeleteGroup_thenThrowException() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.findPositionsByGroupId(1L)).thenReturn(List.of(
                com.portfolio.invest.domain.portfolio.Position.create(10L, 1L, "600519", "贵州茅台", Instant.now())));

        assertThatThrownBy(() -> service.deleteGroup(1L, 1L))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("先清空");
    }

    @DisplayName("删除含现金流水分组被拒")
    @Test
    void givenGroupWithCashFlow_whenDeleteGroup_thenReject() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.findPositionsByGroupId(1L)).thenReturn(List.of());
        when(repo.findCashTransactionsByGroupId(1L)).thenReturn(List.of(
                new CashTransaction(null, 1L, CashTransactionType.DEPOSIT,
                        new BigDecimal("10000"), LocalDate.of(2026, 8, 28), "入金", Instant.now())));

        assertThatThrownBy(() -> service.deleteGroup(1L, 1L))
                .isInstanceOfSatisfying(PortfolioException.class,
                        e -> assertThat(e.code()).isEqualTo(PortfolioErrorCode.GROUP_HAS_CASH_FLOW));
    }

    @DisplayName("创建分组成功")
    @Test
    void givenValidCreateGroupCommand_whenCreateGroup_thenSucceed() {
        when(repo.saveGroup(any())).thenAnswer(inv -> inv.getArgument(0));
        var view = service.createGroup(1L, new CreateGroupCommand("华泰", GroupType.ACCOUNT));
        assertThat(view.name()).isEqualTo("华泰");
    }

    @DisplayName("访问非本人分组抛NOT_FOUND")
    @Test
    void givenOthersGroup_whenDeleteGroup_thenThrowNotFound() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteGroup(1L, 1L))
                .isInstanceOfSatisfying(PortfolioException.class,
                        e -> assertThat(e.code()).isEqualTo(PortfolioErrorCode.NOT_FOUND));
    }

    @DisplayName("买入不存在持仓时新建")
    @Test
    void givenNoExistingPosition_whenBuy_thenCreateNewPosition() {
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

    @DisplayName("卖出调用引擎并保存")
    @Test
    void givenExistingPosition_whenSell_thenInvokeEngineAndSave() {
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

    @DisplayName("编辑买入交易后重放全部交易重算成本与已实现收益")
    @Test
    void givenEditBuyTrade_whenEditTrade_thenReplayAllTradesAndRecalcCostAndPnl() {
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
                new BigDecimal("100"), new BigDecimal("0"), 1L));

        // 重放：买入 100@110（成本 11000），卖出 40@120 → 摊薄成本 110、已实现 400
        assertThat(view.avgCost()).isEqualByComparingTo("110");
        assertThat(view.realizedPnl()).isEqualByComparingTo("400");
        assertThat(view.quantity()).isEqualByComparingTo("60");
    }

    @DisplayName("编辑买入交易可改所属分组")
    @Test
    void givenEditBuyTradeToOtherGroup_whenEditTrade_thenChangeGroup() {
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(positionWithId(5)));
        when(repo.findGroupByIdAndPortfolioId(2L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(2L, 10L, "东财", GroupType.ACCOUNT, Instant.now())));
        when(repo.findPositionByPortfolioIdAndGroupIdAndStockCode(10L, 2L, "600519")).thenReturn(Optional.empty());
        when(repo.findTradeById(11L)).thenReturn(Optional.of(
                new Trade(11L, 5L, TradeType.BUY, LocalDate.of(2026, 8, 27),
                        new BigDecimal("110"), new BigDecimal("100"), new BigDecimal("0"), Instant.now())));
        when(repo.saveTrade(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findTradesByPositionId(5L)).thenReturn(List.of(
                new Trade(11L, 5L, TradeType.BUY, LocalDate.of(2026, 8, 27),
                        new BigDecimal("110"), new BigDecimal("100"), new BigDecimal("0"), Instant.now())));
        when(repo.findDividendsByPositionId(5L)).thenReturn(List.of());
        when(repo.savePosition(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.editTrade(1L, 5L, 11L, new EditTradeCommand(
                LocalDate.of(2026, 8, 27), new BigDecimal("110"),
                new BigDecimal("100"), new BigDecimal("0"), 2L));

        ArgumentCaptor<Position> captor = ArgumentCaptor.forClass(Position.class);
        verify(repo).savePosition(captor.capture());
        assertThat(captor.getValue().groupId()).isEqualTo(2L);
        assertThat(view.groupId()).isEqualTo(2L);
    }

    @DisplayName("编辑买入交易移到已有同代码持仓的分组被拒")
    @Test
    void givenMoveToGroupWithExistingPosition_whenEditTrade_thenReject() {
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(positionWithId(5)));
        when(repo.findGroupByIdAndPortfolioId(2L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(2L, 10L, "东财", GroupType.ACCOUNT, Instant.now())));
        when(repo.findPositionByPortfolioIdAndGroupIdAndStockCode(10L, 2L, "600519"))
                .thenReturn(Optional.of(positionWithId(99L)));
        when(repo.findTradeById(11L)).thenReturn(Optional.of(
                new Trade(11L, 5L, TradeType.BUY, LocalDate.of(2026, 8, 27),
                        new BigDecimal("110"), new BigDecimal("100"), new BigDecimal("0"), Instant.now())));

        assertThatThrownBy(() -> service.editTrade(1L, 5L, 11L, new EditTradeCommand(
                LocalDate.of(2026, 8, 27), new BigDecimal("110"),
                new BigDecimal("100"), new BigDecimal("0"), 2L)))
                .isInstanceOfSatisfying(PortfolioException.class,
                        e -> assertThat(e.code()).isEqualTo(PortfolioErrorCode.INVALID_INPUT));
    }

    @DisplayName("编辑卖出交易被拒绝")
    @Test
    void givenEditSellTrade_whenEditTrade_thenReject() {
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(positionWithId(5)));
        when(repo.findTradeById(12L)).thenReturn(Optional.of(
                new Trade(12L, 5L, TradeType.SELL, LocalDate.of(2026, 8, 28),
                        new BigDecimal("120"), new BigDecimal("40"), new BigDecimal("0"), Instant.now())));

        assertThatThrownBy(() -> service.editTrade(1L, 5L, 12L, new EditTradeCommand(
                LocalDate.of(2026, 8, 28), new BigDecimal("120"),
                new BigDecimal("40"), new BigDecimal("0"), 1L)))
                .isInstanceOfSatisfying(PortfolioException.class,
                        e -> assertThat(e.code()).isEqualTo(PortfolioErrorCode.NOT_FOUND));
    }

    @DisplayName("总览计算总资产与总盈亏")
    @Test
    void givenPositionsAndQuote_whenOverview_thenCalculateTotalAssetsAndPnl() {
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

    @DisplayName("按非本人分组查持仓抛NOT_FOUND")
    @Test
    void givenOthersGroup_whenQueryPositions_thenThrowNotFound() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.positions(1L, 1L))
                .isInstanceOfSatisfying(PortfolioException.class,
                        e -> assertThat(e.code()).isEqualTo(PortfolioErrorCode.NOT_FOUND));
    }

    @DisplayName("编辑其他持仓名下的交易被拒绝")
    @Test
    void givenTradeOfAnotherPosition_whenEditTrade_thenReject() {
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(positionWithId(5)));
        // 交易属于持仓 6，不属于持仓 5：不泄露存在性，一律 NOT_FOUND
        when(repo.findTradeById(11L)).thenReturn(Optional.of(
                new Trade(11L, 6L, TradeType.BUY, LocalDate.of(2026, 8, 27),
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0"), Instant.now())));

        assertThatThrownBy(() -> service.editTrade(1L, 5L, 11L, new EditTradeCommand(
                LocalDate.of(2026, 8, 27), new BigDecimal("110"),
                new BigDecimal("100"), new BigDecimal("0"), 1L)))
                .isInstanceOfSatisfying(PortfolioException.class,
                        e -> assertThat(e.code()).isEqualTo(PortfolioErrorCode.NOT_FOUND));
    }

    @DisplayName("编辑交易后重放含现金分红按除息日顺序重算")
    @Test
    void givenCashDividendAfterBuy_whenEditTrade_thenReplayByExDateOrder() {
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
                new BigDecimal("100"), new BigDecimal("0"), 1L));

        // 重放：先买入 100@100（成本 10000），交易日后再现金分红 1.5*100=150 → 成本 9850
        assertThat(view.quantity()).isEqualByComparingTo("100");
        assertThat(view.avgCost()).isEqualByComparingTo("98.50");
        assertThat(view.cumulativeCashDividend()).isEqualByComparingTo("150");
    }

    @DisplayName("编辑交易后重放含送股且除息日早于后续交易时先分红")
    @Test
    void givenStockDividendExDateBeforeLaterTrade_whenEditTrade_thenApplyDividendFirst() {
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
                new BigDecimal("100"), new BigDecimal("0"), 1L));

        // 重放：买 100@100 → 8-26 除息日早于 8-27 第二笔交易，先送股 100*1.5=150 → 再买 100@100
        // 数量 250，总成本 20000 → 均价 80
        assertThat(view.quantity()).isEqualByComparingTo("250");
        assertThat(view.avgCost()).isEqualByComparingTo("80");
    }

    @DisplayName("配置中行情缺失时忽略该持仓权益")
    @Test
    void givenQuoteMissing_whenAllocation_thenIgnorePositionEquity() {
        when(repo.findPositionsByPortfolioId(10L)).thenReturn(List.of(positionWithId(1)));
        when(repo.findGroupsByPortfolioId(10L)).thenReturn(List.of());
        when(market.quote("600519")).thenThrow(new RuntimeException("无行情"));

        var view = service.allocation(1L);

        assertThat(view.slices().get(0).category().label()).isEqualTo("权益");
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

    /** 全卖出后仍留在仓库（交易历史需保留）的已清仓持仓：数量 0、成本 0、已实现 2000、净现金流 2000。 */
    private Position clearedPosition(long id) {
        var base = Position.create(10L, 1L, "600519", "贵州茅台", Instant.now())
                .applyBuy(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0"))
                .applySell(new BigDecimal("120"), new BigDecimal("100"), new BigDecimal("0"));
        return Position.reconstitute(id, base.portfolioId(), base.groupId(), base.stockCode(), base.stockName(),
                base.quantity(), base.costBasis(), base.totalBuyCost(), base.cumulativeCashDividend(),
                base.realizedPnl(), base.netCashFlow(), base.createdAt(), base.updatedAt());
    }

    @DisplayName("缺省时自动创建组合")
    @Test
    void givenPortfolioAbsent_whenCreateGroup_thenAutoCreatePortfolio() {
        Portfolio created = Portfolio.reconstitute(20L, 2L, CostMethod.WEIGHTED_AVG,
                Instant.now(), Instant.now());
        when(repo.findPortfolioByUserId(2L)).thenReturn(Optional.empty(), Optional.of(created));
        when(repo.saveGroup(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.createGroup(2L, new CreateGroupCommand("华泰", GroupType.ACCOUNT));

        assertThat(view.name()).isEqualTo("华泰");
        verify(repo).insertPortfolioIfAbsent(2L);
    }

    @DisplayName("删除空分组成功")
    @Test
    void givenEmptyGroup_whenDeleteGroup_thenSucceed() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.findPositionsByGroupId(1L)).thenReturn(List.of());

        service.deleteGroup(1L, 1L);

        verify(repo).deleteGroup(1L);
    }

    @DisplayName("改名分组成功")
    @Test
    void givenOwnGroup_whenRenameGroup_thenSucceed() {
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

    @DisplayName("改名非本人分组抛NOT_FOUND")
    @Test
    void givenOthersGroup_whenRenameGroup_thenThrowNotFound() {
        when(repo.findGroupByIdAndPortfolioId(99L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.renameGroup(1L, 99L, new RenameGroupCommand("东财")))
                .isInstanceOfSatisfying(PortfolioException.class,
                        e -> assertThat(e.code()).isEqualTo(PortfolioErrorCode.NOT_FOUND));
    }

    @DisplayName("买入已有持仓累加")
    @Test
    void givenExistingPosition_whenBuy_thenAccumulateQuantity() {
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

    @DisplayName("现金转入转出与分组列表现金余额")
    @Test
    void givenDepositAndWithdraw_whenListGroups_thenReflectCashBalance() {
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

    @DisplayName("现金转入保存并查询")
    @Test
    void givenDepositCommand_whenAddCashTransaction_thenSaveAndQuery() {
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

    @DisplayName("现金转出保存")
    @Test
    void givenWithdrawCommand_whenAddCashTransaction_thenSaveWithdraw() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.saveCashTransaction(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.addCashTransaction(1L, new CashTransactionCommand(1L, CashTransactionType.WITHDRAW,
                new BigDecimal("5000"), LocalDate.of(2026, 8, 28), "转出"));

        assertThat(view.type()).isEqualTo(CashTransactionType.WITHDRAW);
    }

    @DisplayName("现金分红更新持仓并保存")
    @Test
    void givenCashDividendCommand_whenAddCashDividend_thenUpdatePositionAndSave() {
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

    @DisplayName("送股更新持仓并保存")
    @Test
    void givenStockDividendCommand_whenAddStockDividend_thenUpdatePositionAndSave() {
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

    @DisplayName("补录历史现金分红按除息日当时数量且editTrade重放确定性一致")
    @Test
    void givenBackfillDividendBetweenBuys_whenAddCashDividend_thenUseQtyAtExDateAndReplayConsistent() {
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
                new BigDecimal("100"), new BigDecimal("0"), 1L));

        assertThat(replayed.cumulativeCashDividend()).isEqualByComparingTo(view.cumulativeCashDividend());
        assertThat(replayed.quantity()).isEqualByComparingTo("150");
    }

    @DisplayName("删除持仓")
    @Test
    void givenOwnedPosition_whenDeletePosition_thenSucceed() {
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(positionWithId(5)));

        service.deletePosition(1L, 5L);

        verify(repo).deletePosition(5L);
    }

    @DisplayName("查询交易流水")
    @Test
    void givenPositionWithTrades_whenQueryTrades_thenReturnTradeHistory() {
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(positionWithId(5)));
        when(repo.findTradesByPositionId(5L)).thenReturn(List.of(
                new Trade(1L, 5L, TradeType.BUY, LocalDate.of(2026, 8, 27),
                        new BigDecimal("1500"), new BigDecimal("100"), new BigDecimal("5"), Instant.now())));

        var trades = service.trades(1L, 5L);

        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).type()).isEqualTo(TradeType.BUY);
        assertThat(trades.get(0).price()).isEqualByComparingTo("1500");
    }

    @DisplayName("查询分红流水")
    @Test
    void givenPositionWithDividends_whenQueryDividends_thenReturnDividendHistory() {
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(positionWithId(5)));
        when(repo.findDividendsByPositionId(5L)).thenReturn(List.of(
                new Dividend(1L, 5L, DividendType.CASH, LocalDate.of(2026, 8, 28),
                        new BigDecimal("1.5"), null, Instant.now())));

        var dividends = service.dividends(1L, 5L);

        assertThat(dividends).hasSize(1);
        assertThat(dividends.get(0).type()).isEqualTo(DividendType.CASH);
    }

    @DisplayName("查不存在的持仓抛NOT_FOUND")
    @Test
    void givenNonexistentPosition_whenQueryTrades_thenThrowNotFound() {
        when(repo.findPositionByIdAndPortfolioId(999L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.trades(1L, 999L))
                .isInstanceOfSatisfying(PortfolioException.class,
                        e -> assertThat(e.code()).isEqualTo(PortfolioErrorCode.NOT_FOUND));
    }

    @DisplayName("按null组查全部持仓")
    @Test
    void givenNullGroup_whenQueryPositions_thenReturnAllPositions() {
        when(repo.findPositionsByPortfolioId(10L)).thenReturn(List.of(positionWithId(5)));
        when(market.quote("600519")).thenReturn(new Quote(
                "600519", "贵州茅台", 120, 0, 0, 0, 0, 0, 100, 0, 0, null, null, ""));

        var positions = service.positions(1L, null);

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).id()).isEqualTo(5L);
        assertThat(positions.get(0).marketValue()).isEqualByComparingTo("12000");
    }

    @DisplayName("按本人组查持仓")
    @Test
    void givenOwnGroup_whenQueryPositions_thenReturnPositions() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.findPositionsByGroupId(1L)).thenReturn(List.of(positionWithId(5)));
        when(market.quote("600519")).thenReturn(new Quote(
                "600519", "贵州茅台", 120, 0, 0, 0, 0, 0, 100, 0, 0, null, null, ""));

        var positions = service.positions(1L, 1L);

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).groupId()).isEqualTo(1L);
    }

    @DisplayName("总览现价缺失仅计入已实现盈亏")
    @Test
    void givenQuoteMissing_whenOverview_thenCountOnlyRealizedPnl() {
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

    @DisplayName("总览累计现金分红")
    @Test
    void givenPositionWithCashDividend_whenOverview_thenIncludeCumulativeCashDividend() {
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

    @DisplayName("总览忽略非账户分组")
    @Test
    void givenNonAccountGroup_whenOverview_thenIgnoreIt() {
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

    @DisplayName("配置权益与现金占比")
    @Test
    void givenEquityAndCash_whenAllocation_thenComputeRatios() {
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
        assertThat(view.slices().get(0).category().label()).isEqualTo("权益");
        assertThat(view.slices().get(0).marketValue()).isEqualByComparingTo("12000");
        assertThat(view.slices().get(0).ratio()).isEqualByComparingTo("70.59");
        assertThat(view.slices().get(1).category().label()).isEqualTo("现金");
        assertThat(view.slices().get(1).marketValue()).isEqualByComparingTo("5000");
        assertThat(view.slices().get(1).ratio()).isEqualByComparingTo("29.41");
    }

    @DisplayName("空组合配置占比为零")
    @Test
    void givenEmptyPortfolio_whenAllocation_thenRatiosZero() {
        when(repo.findPositionsByPortfolioId(10L)).thenReturn(List.of());
        when(repo.findGroupsByPortfolioId(10L)).thenReturn(List.of());

        var view = service.allocation(1L);

        assertThat(view.slices()).hasSize(2);
        assertThat(view.slices().get(0).ratio()).isEqualByComparingTo("0");
        assertThat(view.slices().get(1).ratio()).isEqualByComparingTo("0");
    }

    @DisplayName("行业分布聚合并排除无映射与无行情")
    @Test
    void givenUnmappedAndNoQuoteStocks_whenIndustryDistribution_thenAggregateExcludingThem() {
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

    @DisplayName("集中度前五与占比")
    @Test
    void givenHoldings_whenConcentration_thenReturnTopFiveAndRatios() {
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

    @DisplayName("批量行情单只失败时跳过该标的不崩页面")
    @Test
    void givenOneQuoteFails_whenConcentration_thenSkipFailedStockWithoutCrashing() {
        when(repo.findPositionsByPortfolioId(10L)).thenReturn(List.of(
                Position.reconstitute(1L, 10L, 1L, "600519", "贵州茅台",
                        new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, Instant.now(), Instant.now()),
                Position.reconstitute(2L, 10L, 1L, "000858", "五粮液",
                        new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, Instant.now(), Instant.now())));
        // 600519 限流失败、000858 有行情
        when(market.quote("600519")).thenThrow(
                new MarketDataException(MarketDataErrorCode.RATE_LIMITED, "共享行情限流 5/s"));
        when(market.quote("000858")).thenReturn(new Quote(
                "000858", "五粮液", 30, 0, 0, 0, 0, 0, 100, 0, 0, null, null, ""));

        var view = service.concentration(1L);

        assertThat(view.holdings()).hasSize(1);
        assertThat(view.holdings().get(0).stockCode()).isEqualTo("000858");
    }

    @DisplayName("全卖出后持仓列表不含已清仓行但buy可重开")
    @Test
    void givenClearedPosition_whenQueryPositions_thenHideRowButBuyCanReopen() {
        when(repo.findPositionsByPortfolioId(10L)).thenReturn(List.of(clearedPosition(5L)));

        assertThat(service.positions(1L, null)).isEmpty();

        // buy 仍能按 组合+分组+代码 命中已清仓行，重新买入（FR-A4：交易历史保留、可再开仓）
        when(repo.findGroupByIdAndPortfolioId(1L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.findPositionByPortfolioIdAndGroupIdAndStockCode(10L, 1L, "600519"))
                .thenReturn(Optional.of(clearedPosition(5L)));
        when(repo.savePosition(any())).thenAnswer(inv -> {
            Position p = inv.getArgument(0);
            return Position.reconstitute(p.id(), p.portfolioId(), p.groupId(), p.stockCode(), p.stockName(),
                    p.quantity(), p.costBasis(), p.totalBuyCost(), p.cumulativeCashDividend(),
                    p.realizedPnl(), p.netCashFlow(), p.createdAt(), p.updatedAt());
        });
        when(repo.saveTrade(any())).thenAnswer(inv -> inv.getArgument(0));

        var reopened = service.buy(1L, new BuyCommand(1L, "600519", "贵州茅台",
                LocalDate.of(2026, 8, 28), new BigDecimal("150"), new BigDecimal("100"), new BigDecimal("5")));

        assertThat(reopened.id()).isEqualTo(5L);
        assertThat(reopened.quantity()).isEqualByComparingTo("100");
    }

    @DisplayName("按组查持仓列表也过滤已清仓行")
    @Test
    void givenClearedRowsInGroup_whenQueryPositionsByGroup_thenFilterClearedRows() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.findPositionsByGroupId(1L)).thenReturn(List.of(clearedPosition(5L)));

        assertThat(service.positions(1L, 1L)).isEmpty();
    }

    @DisplayName("分组持仓数不计已清仓行但现金余额保留其现金流")
    @Test
    void givenClearedPosition_whenListGroups_thenExcludeFromCountButKeepCashBalance() {
        when(repo.findGroupsByPortfolioId(10L)).thenReturn(List.of(
                HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.findPositionsByGroupId(1L)).thenReturn(List.of(clearedPosition(5L)));
        when(repo.findCashTransactionsByGroupId(1L)).thenReturn(List.of());

        var groups = service.groups(1L);

        assertThat(groups.get(0).positionCount()).isZero();
        // 全卖出已实现现金（净现金流 2000）仍需计入该账户分组现金余额
        assertThat(groups.get(0).cashBalance()).isEqualByComparingTo("2000");
    }

    @DisplayName("总览持仓数不计已清仓行但总盈亏保留已实现盈亏")
    @Test
    void givenClearedPosition_whenOverview_thenExcludeFromCountButKeepRealizedPnl() {
        when(repo.findPositionsByPortfolioId(10L)).thenReturn(List.of(clearedPosition(5L)));
        when(repo.findGroupsByPortfolioId(10L)).thenReturn(List.of());

        var view = service.overview(1L);

        assertThat(view.positionCount()).isZero();
        assertThat(view.totalPnl()).isEqualByComparingTo("2000");
    }

    @DisplayName("行业分布忽略已清仓行不产生零值分片")
    @Test
    void givenClearedPosition_whenIndustryDistribution_thenIgnoreWithoutZeroSlices() {
        when(repo.findPositionsByPortfolioId(10L)).thenReturn(List.of(clearedPosition(5L)));
        when(valuation.findAllIndustryMappings()).thenReturn(List.of(
                new ShenwanIndustryMapping("600519", "贵州茅台", "801120", "食品饮料")));
        when(market.quote("600519")).thenReturn(new Quote(
                "600519", "贵州茅台", 120, 0, 0, 0, 0, 0, 100, 0, 0, null, null, ""));

        var view = service.industryDistribution(1L);

        assertThat(view.slices()).isEmpty();
    }
}

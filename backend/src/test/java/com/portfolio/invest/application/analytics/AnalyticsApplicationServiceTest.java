package com.portfolio.invest.application.analytics;

import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.analytics.IndexClosePort;
import com.portfolio.invest.domain.analytics.StockClosePort;
import com.portfolio.invest.domain.portfolio.CashTransaction;
import com.portfolio.invest.domain.portfolio.CashTransactionType;
import com.portfolio.invest.domain.portfolio.CostMethod;
import com.portfolio.invest.domain.portfolio.GroupType;
import com.portfolio.invest.domain.portfolio.HoldingGroup;
import com.portfolio.invest.domain.portfolio.Portfolio;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Position;
import com.portfolio.invest.domain.portfolio.Trade;
import com.portfolio.invest.domain.portfolio.TradeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsApplicationServiceTest {

    private static final LocalDate JAN_02 = LocalDate.of(2026, 1, 2);
    private static final LocalDate JAN_05 = LocalDate.of(2026, 1, 5);
    private static final LocalDate JAN_06 = LocalDate.of(2026, 1, 6);
    private static final LocalDate JAN_07 = LocalDate.of(2026, 1, 7);
    private static final LocalDate JAN_08 = LocalDate.of(2026, 1, 8);
    private static final BigDecimal TEN = new BigDecimal("10");
    private static final BigDecimal ELEVEN = new BigDecimal("11");
    private static final BigDecimal TWELVE = new BigDecimal("12");

    private final PortfolioRepository repo = mock(PortfolioRepository.class);
    private final StockClosePort stockClose = mock(StockClosePort.class);
    private final IndexClosePort indexClose = mock(IndexClosePort.class);
    private final MarketDataService marketData = mock(MarketDataService.class);
    private final AnalyticsApplicationService service =
            new AnalyticsApplicationService(repo, stockClose, indexClose, marketData);

    @DisplayName("无组合无流水 → empty（不创建组合）")
    @Test
    void givenNoPortfolio_whenOverview_thenEmpty() {
        when(repo.findPortfolioByUserId(1L)).thenReturn(Optional.empty());
        assertThat(service.overview(1L)).isEmpty();
        verify(repo, never()).insertPortfolioIfAbsent(any());
    }

    @DisplayName("已知答案：转入+买入+涨10% → TWR 20%、总资产=现金+市值、窗口 2 天")
    @Test
    void givenDepositAndBuy_whenOverview_thenMatchesHandCalc() {
        when(repo.findPortfolioByUserId(1L)).thenReturn(Optional.of(
                Portfolio.reconstitute(9L, 1L, CostMethod.WEIGHTED_AVG, Instant.now(), Instant.now())));
        when(repo.findGroupsByPortfolioId(9L)).thenReturn(List.of(
                HoldingGroup.reconstitute(5L, 9L, "主账户", GroupType.ACCOUNT, Instant.now())));
        when(repo.findCashTransactionsByGroupId(5L)).thenReturn(List.of(
                new CashTransaction(1L, 5L, CashTransactionType.DEPOSIT, new BigDecimal("1000"),
                        JAN_02, null, Instant.now())));
        Position pos = Position.create(9L, 5L, "600519", "贵州茅台", Instant.now());
        when(repo.findPositionsByPortfolioId(9L)).thenReturn(List.of(pos));
        when(repo.findTradesByPositionId(pos.id())).thenReturn(List.of(
                new Trade(1L, pos.id(), TradeType.BUY, JAN_05,
                        TEN, new BigDecimal("100"), BigDecimal.ZERO, Instant.now())));
        when(repo.findDividendsByPositionId(pos.id())).thenReturn(List.of());
        when(stockClose.closes(eq("600519"), any(), any())).thenReturn(stockCloses());
        when(indexClose.closes(anyString(), any(), any())).thenReturn(new TreeMap<>());
        when(marketData.quoteBatch(anyList())).thenReturn(Map.of());

        Optional<OverviewView> ov = service.overview(1L);
        assertThat(ov).isPresent();
        assertThat(ov.get().totalValue()).isEqualByComparingTo("1200");
        assertThat(ov.get().twrCumulative()).isCloseTo(new BigDecimal("0.2"),
                within(new BigDecimal("0.000001")));
        assertThat(ov.get().windowDays()).isEqualTo(2);
        assertThat(ov.get().benchmarks()).isEmpty();
    }

    @DisplayName("nav：组合窗口 01-05~01-07，基准序列截齐——窗口前日期被剔除、无数据基准不出现")
    @Test
    void givenBenchmarkWithDatesOutsideWindow_whenNav_thenSeriesTruncatedToWindow() {
        when(repo.findPortfolioByUserId(1L)).thenReturn(Optional.of(
                Portfolio.reconstitute(9L, 1L, CostMethod.WEIGHTED_AVG, Instant.now(), Instant.now())));
        when(repo.findGroupsByPortfolioId(9L)).thenReturn(List.of(
                HoldingGroup.reconstitute(5L, 9L, "主账户", GroupType.ACCOUNT, Instant.now())));
        when(repo.findCashTransactionsByGroupId(5L)).thenReturn(List.of(
                new CashTransaction(1L, 5L, CashTransactionType.DEPOSIT, new BigDecimal("1000"),
                        JAN_02, null, Instant.now())));
        Position pos = Position.create(9L, 5L, "600519", "贵州茅台", Instant.now());
        when(repo.findPositionsByPortfolioId(9L)).thenReturn(List.of(pos));
        when(repo.findTradesByPositionId(pos.id())).thenReturn(List.of(
                new Trade(1L, pos.id(), TradeType.BUY, JAN_05,
                        TEN, new BigDecimal("100"), BigDecimal.ZERO, Instant.now())));
        when(repo.findDividendsByPositionId(pos.id())).thenReturn(List.of());
        when(stockClose.closes(eq("600519"), any(), any())).thenReturn(stockCloses());
        TreeMap<LocalDate, BigDecimal> hs300 = new TreeMap<>(Map.of(
                LocalDate.of(2026, 1, 4), new BigDecimal("3900"),   // 窗口前——应被截掉
                JAN_05, new BigDecimal("4000"),
                JAN_06, new BigDecimal("4400")));
        when(indexClose.closes(eq("000300"), any(), any())).thenReturn(hs300);
        when(indexClose.closes(eq("000905"), any(), any())).thenReturn(new TreeMap<>());
        when(indexClose.closes(eq("930950"), any(), any())).thenReturn(new TreeMap<>());
        when(marketData.quoteBatch(anyList())).thenReturn(Map.of());

        Optional<NavSeriesView> nav = service.nav(1L);
        assertThat(nav).isPresent();
        NavSeriesView view = nav.get();
        assertThat(view.windowStart()).isEqualTo(JAN_05);
        assertThat(view.windowEnd()).isEqualTo(JAN_07);
        assertThat(view.points()).extracting(NavSeriesView.NavPoint::date)
                .containsExactly(JAN_05, JAN_06, JAN_07);
        assertThat(view.points()).extracting(NavSeriesView.NavPoint::totalValue)
                .usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(List.of(new BigDecimal("1000"), new BigDecimal("1100"), new BigDecimal("1200")));
        assertThat(view.benchmarks()).containsOnlyKeys("000300");
        assertThat(view.benchmarks().get("000300")).extracting(NavSeriesView.IndexPoint::date)
                .containsExactly(JAN_05, JAN_06);
        assertThat(view.benchmarks().get("000300")).extracting(NavSeriesView.IndexPoint::close)
                .usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(List.of(new BigDecimal("4000"), new BigDecimal("4400")));
    }

    @DisplayName("tradeStats 透传：买 100@10、卖 40@13 → realizedPnl 120，持有 3 天，胜率 100%")
    @Test
    void givenBuyAndSell_whenTradeStats_thenCalculatorValuesPassedThrough() {
        when(repo.findPortfolioByUserId(1L)).thenReturn(Optional.of(
                Portfolio.reconstitute(9L, 1L, CostMethod.WEIGHTED_AVG, Instant.now(), Instant.now())));
        when(repo.findGroupsByPortfolioId(9L)).thenReturn(List.of(
                HoldingGroup.reconstitute(5L, 9L, "主账户", GroupType.ACCOUNT, Instant.now())));
        when(repo.findCashTransactionsByGroupId(5L)).thenReturn(List.of());
        Position pos = Position.create(9L, 5L, "600519", "贵州茅台", Instant.now());
        when(repo.findPositionsByPortfolioId(9L)).thenReturn(List.of(pos));
        when(repo.findTradesByPositionId(pos.id())).thenReturn(List.of(
                new Trade(1L, pos.id(), TradeType.BUY, JAN_05,
                        TEN, new BigDecimal("100"), BigDecimal.ZERO, Instant.now()),
                new Trade(2L, pos.id(), TradeType.SELL, JAN_08,
                        new BigDecimal("13"), new BigDecimal("40"), BigDecimal.ZERO, Instant.now())));
        when(repo.findDividendsByPositionId(pos.id())).thenReturn(List.of());
        when(marketData.quoteBatch(anyList())).thenReturn(Map.of());

        Optional<TradeStatsView> stats = service.tradeStats(1L);
        assertThat(stats).isPresent();
        TradeStatsView v = stats.get();
        assertThat(v.sellCount()).isEqualTo(1);
        assertThat(v.winCount()).isEqualTo(1);
        assertThat(v.winRate()).isEqualTo("1.0000");
        assertThat(v.avgWin()).isEqualTo("120.0000");
        assertThat(v.avgLoss()).isEqualTo("0.0000");
        assertThat(v.profitFactor()).isNull();
        assertThat(v.avgHoldingDays()).isEqualTo("3.0000");
        assertThat(v.bestPnl()).isEqualTo("120.0000");
        assertThat(v.worstPnl()).isEqualTo("120.0000");
        // 交易统计纯流水重放——不应触达收盘价端口
        verify(stockClose, never()).closes(anyString(), any(), any());
    }

    @DisplayName("annual：2026 行组合 20%、沪深300 21%、超额 −1%")
    @Test
    void givenBenchmarkCloses_whenAnnual_thenRowWithBenchmarkAndExcess() {
        when(repo.findPortfolioByUserId(1L)).thenReturn(Optional.of(
                Portfolio.reconstitute(9L, 1L, CostMethod.WEIGHTED_AVG, Instant.now(), Instant.now())));
        when(repo.findGroupsByPortfolioId(9L)).thenReturn(List.of(
                HoldingGroup.reconstitute(5L, 9L, "主账户", GroupType.ACCOUNT, Instant.now())));
        when(repo.findCashTransactionsByGroupId(5L)).thenReturn(List.of(
                new CashTransaction(1L, 5L, CashTransactionType.DEPOSIT, new BigDecimal("1000"),
                        JAN_02, null, Instant.now())));
        Position pos = Position.create(9L, 5L, "600519", "贵州茅台", Instant.now());
        when(repo.findPositionsByPortfolioId(9L)).thenReturn(List.of(pos));
        when(repo.findTradesByPositionId(pos.id())).thenReturn(List.of(
                new Trade(1L, pos.id(), TradeType.BUY, JAN_05,
                        TEN, new BigDecimal("100"), BigDecimal.ZERO, Instant.now())));
        when(repo.findDividendsByPositionId(pos.id())).thenReturn(List.of());
        when(stockClose.closes(eq("600519"), any(), any())).thenReturn(stockCloses());
        when(indexClose.closes(eq("000300"), any(), any())).thenReturn(new TreeMap<>(Map.of(
                JAN_05, new BigDecimal("4000"),
                JAN_06, new BigDecimal("4400"),
                JAN_07, new BigDecimal("4840"))));
        when(indexClose.closes(eq("000905"), any(), any())).thenReturn(new TreeMap<>());
        when(indexClose.closes(eq("930950"), any(), any())).thenReturn(new TreeMap<>());
        when(marketData.quoteBatch(anyList())).thenReturn(Map.of());

        List<AnnualReturnRow> rows = service.annual(1L);
        assertThat(rows).hasSize(1);
        AnnualReturnRow row = rows.get(0);
        assertThat(row.year()).isEqualTo(2026);
        assertThat(row.portfolioTwr()).isCloseTo(new BigDecimal("0.2"),
                within(new BigDecimal("0.0001")));
        assertThat(row.benchmarkTwr()).containsOnlyKeys("000300");
        assertThat(row.benchmarkTwr().get("000300")).isCloseTo(new BigDecimal("0.21"),
                within(new BigDecimal("0.0001")));
        assertThat(row.excess()).containsOnlyKeys("000300");
        assertThat(row.excess().get("000300")).isCloseTo(new BigDecimal("-0.01"),
                within(new BigDecimal("0.0001")));
    }

    /** 600519 三日收盘 10 → 11 → 12（补今日实时价的路径由 quoteBatch 空 Map 关闭）。 */
    private static TreeMap<LocalDate, BigDecimal> stockCloses() {
        TreeMap<LocalDate, BigDecimal> closes = new TreeMap<>();
        closes.put(JAN_05, TEN);
        closes.put(JAN_06, ELEVEN);
        closes.put(JAN_07, TWELVE);
        return closes;
    }
}

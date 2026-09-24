package com.portfolio.invest.application.analytics;

import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.analytics.IndexClosePort;
import com.portfolio.invest.domain.analytics.RiskFreeRatePort;
import com.portfolio.invest.domain.analytics.StockClosePort;
import com.portfolio.invest.domain.portfolio.CashTransaction;
import com.portfolio.invest.domain.portfolio.CashTransactionType;
import com.portfolio.invest.domain.portfolio.CostMethod;
import com.portfolio.invest.domain.portfolio.Dividend;
import com.portfolio.invest.domain.portfolio.DividendType;
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
    private static final LocalDate FEB_05 = LocalDate.of(2026, 2, 5);
    private static final BigDecimal TEN = new BigDecimal("10");
    private static final BigDecimal ELEVEN = new BigDecimal("11");
    private static final BigDecimal TWELVE = new BigDecimal("12");
    private static final BigDecimal ONE = new BigDecimal("1");
    private static final BigDecimal NINE = new BigDecimal("9");
    private static final BigDecimal THIRTEEN = new BigDecimal("13");

    private final PortfolioRepository repo = mock(PortfolioRepository.class);
    private final StockClosePort stockClose = mock(StockClosePort.class);
    private final IndexClosePort indexClose = mock(IndexClosePort.class);
    private final MarketDataService marketData = mock(MarketDataService.class);
    private final RiskFreeRatePort riskFree = mock(RiskFreeRatePort.class);
    private final AnalyticsApplicationService service =
            new AnalyticsApplicationService(repo, stockClose, indexClose, marketData, riskFree);

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
        // 有外部现金流 → 走真实 XIRR，非退化口径；5 天 20% 的年化远超二分上界 10 → 无解为 null（前端「—」）
        assertThat(ov.get().irrSimple()).isFalse();
        assertThat(ov.get().irr()).isNull();
    }

    @DisplayName("无外部现金流：IRR 退化为累计收益率并标注口径（spec §三-B/§五-5）")
    @Test
    void givenNoExternalCashFlows_whenOverview_thenIrrDegradesToCumulativeWithFlag() {
        // 只记买入不记转入（无外部现金流的最小真实形态）：investorFlows 空 → XIRR 只剩终值一笔
        // 无从求解，spec 规定退化为累计收益率并在响应中标注口径（irrSimple=true，前端小字提示）。
        // 手算：买入后现金 −1000 → totalValue 序列 [0, 100, 200]（收盘 10→11→12），
        // 首日 V=0 无收益可言被跳过 → TWR 累计 = 200/100 − 1 = 1.0 → 退化 irr = 1.0。
        when(repo.findPortfolioByUserId(1L)).thenReturn(Optional.of(
                Portfolio.reconstitute(9L, 1L, CostMethod.WEIGHTED_AVG, Instant.now(), Instant.now())));
        when(repo.findGroupsByPortfolioId(9L)).thenReturn(List.of());
        Position pos = Position.create(9L, null, "600519", "贵州茅台", Instant.now());
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
        assertThat(ov.get().irrSimple()).isTrue();
        assertThat(ov.get().irr()).isEqualByComparingTo(ov.get().twrCumulative());
        assertThat(ov.get().irr()).isCloseTo(new BigDecimal("1.0"), within(new BigDecimal("0.000001")));
    }

    @DisplayName("同日 SELL+现金分红：先交易后分红（对齐 M08 写侧）——realizedPnl 与直接 Position 聚合一致")
    @Test
    void givenSameDaySellAndCashDividend_whenTradeStats_thenMatchesWriteSidePositionReplay() {
        // 01-05 买100@10；02-05 卖100@12 且同日除息现金分红 1 元/股。
        // M08 写侧「先交易后分红」：卖出时数量仍 100 → realizedPnl=(12−10)×100=200；
        // 分红作用于卖出后余量 0 → 金额 0。若序颠倒（分红先行降成本至 900）realizedPnl
        // 将虚增至 300——本用例防住该回归。
        when(repo.findPortfolioByUserId(1L)).thenReturn(Optional.of(
                Portfolio.reconstitute(9L, 1L, CostMethod.WEIGHTED_AVG, Instant.now(), Instant.now())));
        when(repo.findGroupsByPortfolioId(9L)).thenReturn(List.of());
        Position pos = Position.create(9L, null, "600519", "贵州茅台", Instant.now());
        when(repo.findPositionsByPortfolioId(9L)).thenReturn(List.of(pos));
        when(repo.findTradesByPositionId(pos.id())).thenReturn(List.of(
                new Trade(1L, pos.id(), TradeType.BUY, JAN_05,
                        TEN, new BigDecimal("100"), BigDecimal.ZERO, Instant.now()),
                new Trade(2L, pos.id(), TradeType.SELL, FEB_05,
                        TWELVE, new BigDecimal("100"), BigDecimal.ZERO, Instant.now())));
        when(repo.findDividendsByPositionId(pos.id())).thenReturn(List.of(
                new Dividend(1L, pos.id(), DividendType.CASH, FEB_05, ONE, null, Instant.now())));
        when(marketData.quoteBatch(anyList())).thenReturn(Map.of());

        // 直接 Position 聚合（写侧事实源等价重放）：买 → 卖 → 同日分红按卖出后余量计金额
        Position afterBuySell = Position.create(9L, null, "600519", "贵州茅台", Instant.now())
                .applyBuy(TEN, new BigDecimal("100"), BigDecimal.ZERO)
                .applySell(TWELVE, new BigDecimal("100"), BigDecimal.ZERO);
        Position writeSide = afterBuySell.applyCashDividend(ONE.multiply(afterBuySell.quantity()));

        Optional<TradeStatsView> stats = service.tradeStats(1L);
        assertThat(stats).isPresent();
        TradeStatsView v = stats.get();
        assertThat(v.sellCount()).isEqualTo(1);
        assertThat(new BigDecimal(v.avgWin())).isEqualByComparingTo(writeSide.realizedPnl());
        assertThat(v.avgWin()).isEqualTo("200.0000");
        assertThat(v.winRate()).isEqualTo("1.0000");
        assertThat(v.avgHoldingDays()).isEqualTo("31.0000");
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

    // ———— riskStats ————

    @DisplayName("已知答案：V 形流水 → MDD 25%（峰01-06 谷01-07 恢复01-08），夏普/Calmar 可算")
    @Test
    void givenVShapeFlows_whenRiskStats_thenMddTwentyFiveAndSharpeNotNull() {
        // 手算链（twrIndex 口径）：转入 1000（01-02）→ 01-05 买 100 股@10 → 收盘 10/12/9/13 四日
        // 净值 1000→1200→900→1300（买入后无新外部流）→ 指数 1 → 1.2 → 0.9 → 1.3
        // 峰 1.2（01-06）谷 0.9（01-07）→ MDD = 1 − 0.9/1.2 = 0.25；01-08 指数 1.3 ≥ 1.2 → 已恢复
        // 末点=全程最高 → 当前回撤 0；日收益 0.2/−0.25/+0.4̅4（3 条，sd≠0）→ 夏普可算；
        // rf 空表（无国债数据）→ rfFallback=true 退化 rf=0（编排传端口返回值空 map，不传 null）；
        // 累计 TWR = 0.3、窗口 3 天 → 年化 1.3^(365/3)−1 巨大但有限 → Calmar = 年化/0.25 > 0
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
        when(stockClose.closes(eq("600519"), any(), any())).thenReturn(vShapeCloses());
        when(marketData.quoteBatch(anyList())).thenReturn(Map.of());
        when(riskFree.oneYearSeries(JAN_05, JAN_08)).thenReturn(new TreeMap<>());

        RiskStatsView v = service.riskStats(1L).orElseThrow();
        assertThat(new BigDecimal(v.mdd())).isCloseTo(new BigDecimal("0.25"),
                within(new BigDecimal("0.0001")));
        assertThat(v.peakDate()).isEqualTo("2026-01-06");
        assertThat(v.troughDate()).isEqualTo("2026-01-07");
        assertThat(v.recoveryDate()).isEqualTo("2026-01-08");
        assertThat(v.drawdownDays()).isEqualTo(1);
        assertThat(new BigDecimal(v.currentDrawdown())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(v.windowDays()).isEqualTo(3);
        assertThat(v.sharpe()).isNotNull();
        assertThat(v.sharpeRfFallback()).isTrue();
        assertThat(v.calmar()).isNotNull();
        assertThat(new BigDecimal(v.calmar())).isGreaterThan(BigDecimal.ZERO);
    }

    @DisplayName("无流水 → empty（Controller 204）")
    @Test
    void givenNoFlows_whenRiskStats_thenEmpty() {
        when(repo.findPortfolioByUserId(1L)).thenReturn(Optional.empty());
        assertThat(service.riskStats(1L)).isEmpty();
        verify(repo, never()).insertPortfolioIfAbsent(any());
    }

    /** 600519 三日收盘 10 → 11 → 12（补今日实时价的路径由 quoteBatch 空 Map 关闭）。 */
    private static TreeMap<LocalDate, BigDecimal> stockCloses() {
        TreeMap<LocalDate, BigDecimal> closes = new TreeMap<>();
        closes.put(JAN_05, TEN);
        closes.put(JAN_06, ELEVEN);
        closes.put(JAN_07, TWELVE);
        return closes;
    }

    /** 600519 四日 V 形收盘 10 → 12 → 9 → 13（涨 20% → 跌 25% → 涨 44.4%）。 */
    private static TreeMap<LocalDate, BigDecimal> vShapeCloses() {
        TreeMap<LocalDate, BigDecimal> closes = new TreeMap<>();
        closes.put(JAN_05, TEN);
        closes.put(JAN_06, TWELVE);
        closes.put(JAN_07, NINE);
        closes.put(JAN_08, THIRTEEN);
        return closes;
    }
}

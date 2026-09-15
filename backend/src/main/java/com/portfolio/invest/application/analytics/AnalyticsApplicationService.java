package com.portfolio.invest.application.analytics;

import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.analytics.AnnualReturnCalculator;
import com.portfolio.invest.domain.analytics.CashEvent;
import com.portfolio.invest.domain.analytics.DailyPoint;
import com.portfolio.invest.domain.analytics.DatedAmount;
import com.portfolio.invest.domain.analytics.ExternalFlow;
import com.portfolio.invest.domain.analytics.IndexClosePort;
import com.portfolio.invest.domain.analytics.IrrCalculator;
import com.portfolio.invest.domain.analytics.NavReconstructor;
import com.portfolio.invest.domain.analytics.NavSeries;
import com.portfolio.invest.domain.analytics.StockClosePort;
import com.portfolio.invest.domain.analytics.StockEvent;
import com.portfolio.invest.domain.analytics.TradeStatsCalculator;
import com.portfolio.invest.domain.analytics.TwrCalculator;
import com.portfolio.invest.domain.portfolio.CashTransaction;
import com.portfolio.invest.domain.portfolio.CashTransactionType;
import com.portfolio.invest.domain.portfolio.Dividend;
import com.portfolio.invest.domain.portfolio.DividendType;
import com.portfolio.invest.domain.portfolio.HoldingGroup;
import com.portfolio.invest.domain.portfolio.Portfolio;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Position;
import com.portfolio.invest.domain.portfolio.Trade;
import com.portfolio.invest.domain.portfolio.TradeType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * 收益分析编排（MS-07）：取 portfolio 流水 → 单趟重放真实 Position 聚合 → 调 domain/analytics
 * 纯函数 → 组装四个只读 View。零落库（读侧重算），方法不加事务注解（对照 portfolio 读方法惯例）。
 */
@Service
public class AnalyticsApplicationService {

    /** 三只基准（与 collector INDEX_CLOSE_CODES 同源）；LinkedHashMap 保展示序。 */
    private static final Map<String, String> BENCHMARKS = benchmarks();

    /** 同日事件稳定序：BUY(0) → 现金/送股股息(1) → SELL(2)。 */
    private static final int RANK_BUY = 0;
    private static final int RANK_DIVIDEND = 1;
    private static final int RANK_SELL = 2;

    private static final Comparator<TimedEvent> EVENT_ORDER = Comparator
            .comparing(TimedEvent::date)
            .thenComparingInt(TimedEvent::rank)
            .thenComparing(TimedEvent::createdAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(TimedEvent::id, Comparator.nullsLast(Comparator.naturalOrder()));

    private final PortfolioRepository portfolioRepository;
    private final StockClosePort stockClose;
    private final IndexClosePort indexClose;
    private final MarketDataService marketData;

    public AnalyticsApplicationService(PortfolioRepository portfolioRepository,
            StockClosePort stockClose, IndexClosePort indexClose, MarketDataService marketData) {
        this.portfolioRepository = portfolioRepository;
        this.stockClose = stockClose;
        this.indexClose = indexClose;
        this.marketData = marketData;
    }

    /** 总览卡：TWR 累计/年化、IRR（无现金流/无解 → null）、总资产、三基准同期收益与超额。 */
    public Optional<OverviewView> overview(Long userId) {
        Optional<Replay> replay = replay(userId);
        if (replay.isEmpty()) {
            return Optional.empty();
        }
        List<DailyPoint> pts = reconstruct(replay.get()).points();
        if (pts.isEmpty()) {
            return Optional.empty();
        }
        DailyPoint first = pts.get(0);
        DailyPoint last = pts.get(pts.size() - 1);
        long windowDays = ChronoUnit.DAYS.between(first.tradeDate(), last.tradeDate());
        BigDecimal cumulative = TwrCalculator.cumulative(new NavSeries(pts), replay.get().externalFlows());
        BigDecimal annualized = TwrCalculator.annualized(cumulative, windowDays);
        // XIRR：投资者视角现金流（出资 −、回收 +）+ 终值；无外部现金流时只剩终值一笔 → xirr empty → null
        List<DatedAmount> xirrFlows = new ArrayList<>(replay.get().investorFlows());
        xirrFlows.add(new DatedAmount(last.tradeDate(), last.totalValue()));
        BigDecimal irr = IrrCalculator.xirr(xirrFlows).map(AnalyticsApplicationService::round4).orElse(null);
        Map<String, OverviewView.BenchmarkComparison> benchmarks = new LinkedHashMap<>();
        for (var e : benchmarkTwr(first.tradeDate(), last.tradeDate()).entrySet()) {
            benchmarks.put(e.getKey(), new OverviewView.BenchmarkComparison(
                    e.getKey(), BENCHMARKS.get(e.getKey()), round4(e.getValue()),
                    round4(cumulative.subtract(e.getValue()))));
        }
        return Optional.of(new OverviewView(round4(last.totalValue()), round4(cumulative),
                round4(annualized), irr, windowDays, benchmarks));
    }

    /** 走势图：组合 totalValue 绝对值日序列 + 三基准收盘序列（截齐到组合窗口，归一化留前端）。 */
    public Optional<NavSeriesView> nav(Long userId) {
        Optional<Replay> replay = replay(userId);
        if (replay.isEmpty()) {
            return Optional.empty();
        }
        List<DailyPoint> pts = reconstruct(replay.get()).points();
        if (pts.isEmpty()) {
            return Optional.empty();
        }
        LocalDate windowStart = pts.get(0).tradeDate();
        LocalDate windowEnd = pts.get(pts.size() - 1).tradeDate();
        List<NavSeriesView.NavPoint> points = pts.stream()
                .map(p -> new NavSeriesView.NavPoint(p.tradeDate(), round4(p.totalValue())))
                .toList();
        Map<String, List<NavSeriesView.IndexPoint>> benchmarks = new LinkedHashMap<>();
        for (String code : BENCHMARKS.keySet()) {
            SortedMap<LocalDate, BigDecimal> closes = benchmarkCloses(code, windowStart, windowEnd);
            if (!closes.isEmpty()) {
                benchmarks.put(code, closes.entrySet().stream()
                        .map(c -> new NavSeriesView.IndexPoint(c.getKey(), round4(c.getValue())))
                        .toList());
            }
        }
        return Optional.of(new NavSeriesView(windowStart, windowEnd, points, benchmarks));
    }

    /** 年度表：年份 ×（组合 TWR、各基准同年 TWR、超额 = 组合 − 基准）。 */
    public List<AnnualReturnRow> annual(Long userId) {
        Optional<Replay> replay = replay(userId);
        if (replay.isEmpty()) {
            return List.of();
        }
        List<DailyPoint> pts = reconstruct(replay.get()).points();
        if (pts.isEmpty()) {
            return List.of();
        }
        LocalDate windowStart = pts.get(0).tradeDate();
        LocalDate windowEnd = pts.get(pts.size() - 1).tradeDate();
        java.util.SortedMap<Integer, BigDecimal> portfolio =
                AnnualReturnCalculator.yearlyTwr(new NavSeries(pts), replay.get().externalFlows());
        Map<String, java.util.SortedMap<Integer, BigDecimal>> benchmarkYearly = new LinkedHashMap<>();
        for (String code : BENCHMARKS.keySet()) {
            SortedMap<LocalDate, BigDecimal> closes = benchmarkCloses(code, windowStart, windowEnd);
            if (!closes.isEmpty()) {
                benchmarkYearly.put(code, AnnualReturnCalculator.yearlyTwr(asSeries(closes), List.of()));
            }
        }
        List<AnnualReturnRow> rows = new ArrayList<>();
        for (var year : portfolio.entrySet()) {
            Map<String, BigDecimal> benchmarkTwr = new LinkedHashMap<>();
            Map<String, BigDecimal> excess = new LinkedHashMap<>();
            for (var b : benchmarkYearly.entrySet()) {
                BigDecimal twr = b.getValue().get(year.getKey());
                if (twr != null) {
                    benchmarkTwr.put(b.getKey(), round4(twr));
                    excess.put(b.getKey(), round4(year.getValue().subtract(twr)));
                }
            }
            rows.add(new AnnualReturnRow(year.getKey(), round4(year.getValue()), benchmarkTwr, excess));
        }
        return rows;
    }

    /** 交易统计：重放产出的 BuyLot/SellLot 透传域计算器；数值 toPlainString（profitFactor null → 前端 "—"）。 */
    public Optional<TradeStatsView> tradeStats(Long userId) {
        Optional<Replay> replay = replay(userId);
        if (replay.isEmpty() || (replay.get().buys().isEmpty() && replay.get().sells().isEmpty())) {
            return Optional.empty();
        }
        TradeStatsCalculator.TradeStats s =
                TradeStatsCalculator.stats(replay.get().buys(), replay.get().sells());
        return Optional.of(new TradeStatsView(s.sellCount(), s.winCount(),
                plain(s.winRate()), plain(s.avgWin()), plain(s.avgLoss()), plain(s.profitFactor()),
                plain(s.avgHoldingDays()), plain(s.bestPnl()), plain(s.worstPnl())));
    }

    // ---------- 取数与重放 ----------

    /** 一次取数重放的中间产物：重建事件流 + TWR 外部流 + XIRR 投资者流 + 交易统计批次。 */
    private record Replay(List<StockEvent> stockEvents, List<CashEvent> cashEvents,
            List<ExternalFlow> externalFlows, List<DatedAmount> investorFlows,
            List<TradeStatsCalculator.BuyLot> buys, List<TradeStatsCalculator.SellLot> sells) {}

    /** 持仓内事件统一排序载体（Trade 与 Dividend 二选一非空）。 */
    private record TimedEvent(LocalDate date, int rank, java.time.Instant createdAt, Long id,
            Trade trade, Dividend dividend) {}

    private Optional<Replay> replay(Long userId) {
        Optional<Portfolio> portfolio = portfolioRepository.findPortfolioByUserId(userId);
        if (portfolio.isEmpty()) {
            return Optional.empty();  // 不创建组合——分析是读侧，绝不写库
        }
        Long portfolioId = portfolio.get().id();
        List<Position> positions = portfolioRepository.findPositionsByPortfolioId(portfolioId);
        List<HoldingGroup> groups = portfolioRepository.findGroupsByPortfolioId(portfolioId);
        if (positions.isEmpty() && groups.isEmpty()) {
            return Optional.empty();
        }
        List<StockEvent> stockEvents = new ArrayList<>();
        List<CashEvent> cashEvents = new ArrayList<>();
        List<ExternalFlow> externalFlows = new ArrayList<>();
        List<DatedAmount> investorFlows = new ArrayList<>();
        List<TradeStatsCalculator.BuyLot> buys = new ArrayList<>();
        List<TradeStatsCalculator.SellLot> sells = new ArrayList<>();
        // 持仓流水按对象引用逐个重放（positionId 可能为 null 的 create 产物不能作分组键）
        for (Position pos : positions) {
            replayPosition(pos, stockEvents, cashEvents, buys, sells);
        }
        // 分组现金流水：转入/转出同时是外部现金流（TWR 剔除）与投资者现金流（XIRR 出资 −/回收 +）
        for (HoldingGroup group : groups) {
            for (CashTransaction tx : portfolioRepository.findCashTransactionsByGroupId(group.id())) {
                BigDecimal signed = tx.type() == CashTransactionType.DEPOSIT
                        ? tx.amount() : tx.amount().negate();
                cashEvents.add(new CashEvent(tx.txDate(), signed));
                externalFlows.add(new ExternalFlow(tx.txDate(), signed));
                investorFlows.add(new DatedAmount(tx.txDate(), signed.negate()));
            }
        }
        if (stockEvents.isEmpty() && cashEvents.isEmpty()) {
            return Optional.empty();  // 无流水 → NO_DATA 语义（Controller 204）
        }
        return Optional.of(new Replay(stockEvents, cashEvents, externalFlows, investorFlows, buys, sells));
    }

    /** 单持仓重放：真实 Position 聚合走 applyBuy/applySell/apply*，realizedPnl 取前后差值（单一事实源）。 */
    private void replayPosition(Position pos, List<StockEvent> stockEvents, List<CashEvent> cashEvents,
            List<TradeStatsCalculator.BuyLot> buys, List<TradeStatsCalculator.SellLot> sells) {
        List<TimedEvent> flow = new ArrayList<>();
        for (Trade t : portfolioRepository.findTradesByPositionId(pos.id())) {
            flow.add(new TimedEvent(t.tradeDate(), t.type() == TradeType.BUY ? RANK_BUY : RANK_SELL,
                    t.createdAt(), t.id(), t, null));
        }
        for (Dividend d : portfolioRepository.findDividendsByPositionId(pos.id())) {
            flow.add(new TimedEvent(d.exDate(), RANK_DIVIDEND, d.createdAt(), d.id(), null, d));
        }
        flow.sort(EVENT_ORDER);
        Position agg = Position.create(pos.portfolioId(), pos.groupId(),
                pos.stockCode(), pos.stockName(), pos.createdAt());
        for (TimedEvent e : flow) {
            if (e.trade() != null) {
                Trade t = e.trade();
                BigDecimal fee = zeroIfNull(t.fee());
                if (t.type() == TradeType.BUY) {
                    agg = agg.applyBuy(t.price(), t.quantity(), fee);
                    stockEvents.add(new StockEvent(t.tradeDate(), pos.stockCode(), t.quantity()));
                    cashEvents.add(new CashEvent(t.tradeDate(),
                            t.price().multiply(t.quantity()).add(fee).negate()));
                    buys.add(new TradeStatsCalculator.BuyLot(t.tradeDate(), t.quantity()));
                } else {
                    BigDecimal realizedBefore = agg.realizedPnl();
                    agg = agg.applySell(t.price(), t.quantity(), fee);
                    sells.add(new TradeStatsCalculator.SellLot(t.tradeDate(), t.quantity(),
                            agg.realizedPnl().subtract(realizedBefore)));
                    stockEvents.add(new StockEvent(t.tradeDate(), pos.stockCode(), t.quantity().negate()));
                    cashEvents.add(new CashEvent(t.tradeDate(),
                            t.price().multiply(t.quantity()).subtract(fee)));
                }
            } else if (e.dividend().type() == DividendType.CASH) {
                BigDecimal total = zeroIfNull(e.dividend().cashPerShare()).multiply(agg.quantity());
                agg = agg.applyCashDividend(total);
                cashEvents.add(new CashEvent(e.dividend().exDate(), total));
            } else {
                BigDecimal ratio = zeroIfNull(e.dividend().stockRatio());
                BigDecimal bonusQty = ratio.multiply(agg.quantity());
                agg = agg.applyStockDividend(ratio);
                stockEvents.add(new StockEvent(e.dividend().exDate(), pos.stockCode(), bonusQty));
            }
        }
    }

    // ---------- 收盘价与序列 ----------

    /** 流水事件 → 组合日序列：首事件日（含现金流水）起至今，今日 close 未落库用 quoteBatch 实时价补（spec §五-4）。 */
    private NavSeries reconstruct(Replay replay) {
        LocalDate firstEvent = java.util.stream.Stream.concat(
                        replay.stockEvents().stream().map(StockEvent::date),
                        replay.cashEvents().stream().map(CashEvent::date))
                .min(LocalDate::compareTo)
                .orElseThrow();  // replay() 已保证至少一个事件
        LocalDate today = LocalDate.now();
        List<String> codes = replay.stockEvents().stream().map(StockEvent::stockCode).distinct().toList();
        return NavReconstructor.reconstruct(firstEvent, today,
                replay.stockEvents(), replay.cashEvents(), fetchCloses(codes, firstEvent, today));
    }

    private Map<String, SortedMap<LocalDate, BigDecimal>> fetchCloses(List<String> codes,
            LocalDate from, LocalDate to) {
        Map<String, SortedMap<LocalDate, BigDecimal>> closes = new HashMap<>();
        for (String code : codes) {
            closes.put(code, stockClose.closes(code, from, to));
        }
        if (!codes.isEmpty()) {
            // 今日 close 未落库 → 实时价补位；单只失败仅止于昨日（不阻断，spec §五-4）
            marketData.quoteBatch(codes).forEach((code, quote) -> {
                SortedMap<LocalDate, BigDecimal> series = closes.get(code);
                if (series != null && !series.containsKey(to) && quote.price() > 0) {
                    series.put(to, BigDecimal.valueOf(quote.price()));
                }
            });
        }
        return closes;
    }

    /** 基准收盘截齐到组合窗口 [from, to]（端口契约已 BETWEEN，显式过滤防实现越界，spec §五-3）。 */
    private SortedMap<LocalDate, BigDecimal> benchmarkCloses(String indexCode, LocalDate from, LocalDate to) {
        SortedMap<LocalDate, BigDecimal> out = new TreeMap<>();
        indexClose.closes(indexCode, from, to).forEach((day, close) -> {
            if (!day.isBefore(from) && !day.isAfter(to)) {
                out.put(day, close);
            }
        });
        return out;
    }

    /** 基准同期 TWR：收盘序列合成无外部流 NavSeries 后走同一 TwrCalculator（≥2 个点位才算得出）。 */
    private Map<String, BigDecimal> benchmarkTwr(LocalDate from, LocalDate to) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String code : BENCHMARKS.keySet()) {
            SortedMap<LocalDate, BigDecimal> closes = benchmarkCloses(code, from, to);
            if (closes.size() >= 2) {
                out.put(code, TwrCalculator.cumulative(asSeries(closes), List.of()));
            }
        }
        return out;
    }

    private NavSeries asSeries(SortedMap<LocalDate, BigDecimal> closes) {
        return new NavSeries(closes.entrySet().stream()
                .map(e -> new DailyPoint(e.getKey(), e.getValue(), BigDecimal.ZERO, e.getValue()))
                .toList());
    }

    // ---------- 小工具 ----------

    private static BigDecimal round4(BigDecimal v) {
        return v.setScale(4, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    private static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static Map<String, String> benchmarks() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("000300", "沪深300");
        m.put("000905", "中证500");
        m.put("930950", "中证偏股基金指数");
        return Collections.unmodifiableMap(m);
    }
}

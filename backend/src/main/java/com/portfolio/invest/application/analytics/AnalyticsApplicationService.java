package com.portfolio.invest.application.analytics;

import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.analytics.AnnualReturnCalculator;
import com.portfolio.invest.domain.analytics.AttributionCalculator;
import com.portfolio.invest.domain.analytics.BenchmarkIndustryWeightPort;
import com.portfolio.invest.domain.analytics.CashEvent;
import com.portfolio.invest.domain.analytics.DailyPoint;
import com.portfolio.invest.domain.analytics.DatedAmount;
import com.portfolio.invest.domain.analytics.DatedIndex;
import com.portfolio.invest.domain.analytics.DatedReturn;
import com.portfolio.invest.domain.analytics.ExternalFlow;
import com.portfolio.invest.domain.analytics.IndexClosePort;
import com.portfolio.invest.domain.analytics.IndustryMappingPort;
import com.portfolio.invest.domain.analytics.IrrCalculator;
import com.portfolio.invest.domain.analytics.NavReconstructor;
import com.portfolio.invest.domain.analytics.NavSeries;
import com.portfolio.invest.domain.analytics.RiskFreeRatePort;
import com.portfolio.invest.domain.analytics.RiskMetricsCalculator;
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
import java.math.MathContext;
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
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * 收益分析编排（MS-07/MS-13）：取 portfolio 流水 → 单趟重放真实 Position 聚合 → 调 domain/analytics
 * 纯函数 → 组装只读 View。零落库（读侧重算），方法不加事务注解（对照 portfolio 读方法惯例）。
 */
@Service
public class AnalyticsApplicationService {

    /** 三只基准（与 collector INDEX_CLOSE_CODES 同源）；LinkedHashMap 保展示序。 */
    private static final Map<String, String> BENCHMARKS = benchmarks();

    /** 归因基准=沪深300（spec 澄清 #1）。 */
    private static final String BENCHMARK_CODE = "000300";

    /** 归因逐日组装的除法精度（非终止小数截断；贡献值最终由计算器 scale(10) 收口）。 */
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365);

    /** 同日事件稳定序：BUY(0) → SELL(1) → 现金/送股股息(2)——对齐 M08 写侧「先交易后分红」（单一事实源）。 */
    private static final int RANK_BUY = 0;
    private static final int RANK_SELL = 1;
    private static final int RANK_DIVIDEND = 2;

    private static final Comparator<TimedEvent> EVENT_ORDER = Comparator
            .comparing(TimedEvent::date)
            .thenComparingInt(TimedEvent::rank)
            .thenComparing(TimedEvent::createdAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(TimedEvent::id, Comparator.nullsLast(Comparator.naturalOrder()));

    private final PortfolioRepository portfolioRepository;
    private final StockClosePort stockClose;
    private final IndexClosePort indexClose;
    private final MarketDataService marketData;
    private final RiskFreeRatePort riskFree;
    private final IndustryMappingPort industryMapping;
    private final BenchmarkIndustryWeightPort benchmarkWeight;

    public AnalyticsApplicationService(PortfolioRepository portfolioRepository,
            StockClosePort stockClose, IndexClosePort indexClose, MarketDataService marketData,
            RiskFreeRatePort riskFree, IndustryMappingPort industryMapping,
            BenchmarkIndustryWeightPort benchmarkWeight) {
        this.portfolioRepository = portfolioRepository;
        this.stockClose = stockClose;
        this.indexClose = indexClose;
        this.marketData = marketData;
        this.riskFree = riskFree;
        this.industryMapping = industryMapping;
        this.benchmarkWeight = benchmarkWeight;
    }

    /** 总览卡：TWR 累计/年化、IRR（无解 → null；无外部现金流 → 退化累计收益并标注）、总资产、三基准同期收益与超额。 */
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
        // XIRR：投资者视角现金流（出资 −、回收 +）+ 终值；无外部现金流时只剩终值一笔无从求解，
        // 按 spec §三-B/§五-5 退化为累计收益率并标注口径（irrSimple，前端小字提示）
        List<DatedAmount> xirrFlows = new ArrayList<>(replay.get().investorFlows());
        xirrFlows.add(new DatedAmount(last.tradeDate(), last.totalValue()));
        BigDecimal irr = IrrCalculator.xirr(xirrFlows).map(AnalyticsApplicationService::round4).orElse(null);
        boolean irrSimple = replay.get().investorFlows().isEmpty();
        if (irrSimple) {
            irr = round4(cumulative);
        }
        Map<String, OverviewView.BenchmarkComparison> benchmarks = new LinkedHashMap<>();
        for (var e : benchmarkTwr(first.tradeDate(), last.tradeDate()).entrySet()) {
            benchmarks.put(e.getKey(), new OverviewView.BenchmarkComparison(
                    e.getKey(), BENCHMARKS.get(e.getKey()), round4(e.getValue()),
                    round4(cumulative.subtract(e.getValue()))));
        }
        return Optional.of(new OverviewView(round4(last.totalValue()), round4(cumulative),
                round4(annualized), irr, irrSimple, windowDays, benchmarks));
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

    /** 风险指标（MS-13 F07/F08）：回撤基于 TWR 净值指数（spec §2.2），夏普 rf=1Y 国债。 */
    public Optional<RiskStatsView> riskStats(Long userId) {
        Optional<Replay> replay = replay(userId);
        if (replay.isEmpty()) {
            return Optional.empty();
        }
        List<DailyPoint> pts = reconstruct(replay.get()).points();
        if (pts.size() < 2) {
            return Optional.empty();
        }
        List<DatedIndex> idx = RiskMetricsCalculator.twrIndex(new NavSeries(pts), replay.get().externalFlows());
        RiskMetricsCalculator.MddResult mdd = RiskMetricsCalculator.maxDrawdown(idx);
        // rf 端口空表 → 空 map 直传（夏普内部退化 rf=0；字面 null 会 NPE，勿传）
        SortedMap<LocalDate, BigDecimal> rf = riskFree.oneYearSeries(
                pts.get(0).tradeDate(), pts.get(pts.size() - 1).tradeDate());
        RiskMetricsCalculator.SharpeResult sharpe = RiskMetricsCalculator.sharpe(dailyReturns(idx), rf);
        long windowDays = ChronoUnit.DAYS.between(pts.get(0).tradeDate(), pts.get(pts.size() - 1).tradeDate());
        BigDecimal cumulative = TwrCalculator.cumulative(new NavSeries(pts), replay.get().externalFlows());
        BigDecimal calmar = RiskMetricsCalculator.calmar(TwrCalculator.annualized(cumulative, windowDays), mdd);
        long drawdownDays = mdd.peakDate() == null ? 0
                : java.util.stream.IntStream.range(0, idx.size())
                        .filter(i -> idx.get(i).date().equals(mdd.troughDate())).findFirst().orElse(0)
                - java.util.stream.IntStream.range(0, idx.size())
                        .filter(i -> idx.get(i).date().equals(mdd.peakDate())).findFirst().orElse(0);
        return Optional.of(new RiskStatsView(
                plain(mdd.mdd()),
                plain(mdd.currentDrawdown()),
                mdd.peakDate() == null ? null : mdd.peakDate().toString(),
                mdd.troughDate() == null ? null : mdd.troughDate().toString(),
                mdd.recoveryDate() == null ? null : mdd.recoveryDate().toString(),
                drawdownDays,
                plain(sharpe.value()),
                sharpe.rfFallback(),
                plain(calmar),
                windowDays));
    }

    /** 归因（MS-13 F09）：日频子周期 Brinson 对沪深300；组合行业/现金权重=前一日收盘快照（子周期期初），
     * 基准行业权重=成分快照（静态近似）；行业指数缺日容忍（缺键跳过、残差留痕）；窗口=三方交集。 */
    public Optional<AttributionView> attribution(Long userId) {
        Optional<Replay> replay = replay(userId);
        if (replay.isEmpty()) {
            return Optional.empty();
        }
        List<DailyPoint> pts = reconstruct(replay.get()).points();
        if (pts.size() < 2) {
            return Optional.empty();
        }
        LocalDate from = pts.get(0).tradeDate();
        LocalDate to = pts.get(pts.size() - 1).tradeDate();
        // 1) 个股收盘（复用既有取数：今日 quoteBatch 实时价补位）
        List<String> codes = replay.get().stockEvents().stream()
                .map(StockEvent::stockCode).distinct().toList();
        Map<String, SortedMap<LocalDate, BigDecimal>> closes = fetchCloses(new ArrayList<>(codes), from, to);
        Map<String, IndustryMappingPort.IndustryRef> mapping = industryMapping.byStock();
        // 2) 行业指数与基准收盘（复用 IndexClosePort；行业码与 shenwan_industry_mapping 同构）
        Map<String, SortedMap<LocalDate, BigDecimal>> industryCloses = new LinkedHashMap<>();
        for (String industryCode : new TreeSet<>(mapping.values().stream()
                .map(IndustryMappingPort.IndustryRef::industryCode).toList())) {
            SortedMap<LocalDate, BigDecimal> series = benchmarkCloses(industryCode, from, to);
            if (!series.isEmpty()) {
                industryCloses.put(industryCode, series);
            }
        }
        SortedMap<LocalDate, BigDecimal> benchmark = benchmarkCloses(BENCHMARK_CODE, from, to);
        Map<String, BigDecimal> benchmarkWeights = benchmarkWeight.industryWeights(BENCHMARK_CODE);
        // rf 端口空表 → 空 map（端口契约；字面 null 会 NPE，勿传）
        SortedMap<LocalDate, BigDecimal> rf = riskFree.oneYearSeries(from, to);
        // 3) 逐日组装 DailyRow（个股市值按映射聚合 → 前一日权重/当日子周期收益；日期三方交集）
        AttributionInputs inputs = buildDailyRows(pts, replay.get(), closes, mapping,
                industryCloses, benchmark, benchmarkWeights, rf);
        AttributionCalculator.AttributionResult res = AttributionCalculator.attribute(inputs.rows());
        // 4) View：industryName 从 mapping 反查；UNMAPPED 桶显示名兜底
        Map<String, String> names = new HashMap<>();
        mapping.values().forEach(r -> names.put(r.industryCode(), r.industryName()));
        List<AttributionView.Row> rows = res.rows().stream()
                .map(r -> new AttributionView.Row(r.industry(),
                        names.getOrDefault(r.industry(), "未映射行业"),
                        plain(r.allocation()), plain(r.selection())))
                .toList();
        return Optional.of(new AttributionView(
                res.windowStart() == null ? null : res.windowStart().toString(),
                res.windowEnd() == null ? null : res.windowEnd().toString(),
                rows, plain(res.cashAllocation()), plain(res.totalExcess()),
                plain(res.residual()), plain(inputs.unmappedValueShare())));
    }

    /** buildDailyRows 产物：DailyRow 列表 + 未映射个股市值占比（各行 UNMAPPED 权重的均值）。 */
    private record AttributionInputs(List<AttributionCalculator.DailyRow> rows,
            BigDecimal unmappedValueShare) {}

    /**
     * 逐「前一日→当日」对组装 DailyRow：个股市值=事件累积数量×收盘（close 缺失 forward-fill）按映射
     * 聚行业（无映射归 UNMAPPED）；权重=前一日快照（子周期期初，Σwp+wc=1 与子周期收益构成精确恒等
     * 分解）；Rp 用 TWR 外部流剔除口径 (V_t−F_t)/V_{t−1}−1；Rb_i/Rb 由指数序列相邻日推，当日不在
     * 基准序列或无前值 → 跳过（组合∩行业∩基准三方交集）；rfDaily=1Y 国债百分数 ÷100÷365（缺失日
     * forward-fill，全缺=0）。
     */
    private AttributionInputs buildDailyRows(List<DailyPoint> pts, Replay replay,
            Map<String, SortedMap<LocalDate, BigDecimal>> closes,
            Map<String, IndustryMappingPort.IndustryRef> mapping,
            Map<String, SortedMap<LocalDate, BigDecimal>> industryCloses,
            SortedMap<LocalDate, BigDecimal> benchmark,
            Map<String, BigDecimal> benchmarkWeights,
            SortedMap<LocalDate, BigDecimal> rfPercent) {
        // 个股日数量序列：StockEvent 按日累积（BUY + / SELL − / 送股 +），与 pts 对齐产出行业市值快照
        List<StockEvent> events = replay.stockEvents().stream()
                .sorted(Comparator.comparing(StockEvent::date)).toList();
        List<Map<String, BigDecimal>> industryMvByDay = new ArrayList<>(pts.size());
        Map<String, BigDecimal> qty = new LinkedHashMap<>();
        int ei = 0;
        for (DailyPoint p : pts) {
            while (ei < events.size() && !events.get(ei).date().isAfter(p.tradeDate())) {
                qty.merge(events.get(ei).stockCode(), events.get(ei).qtyDelta(), BigDecimal::add);
                ei++;
            }
            Map<String, BigDecimal> mv = new LinkedHashMap<>();
            for (var held : qty.entrySet()) {
                if (held.getValue().signum() == 0) {
                    continue;
                }
                SortedMap<LocalDate, BigDecimal> series = closes.get(held.getKey());
                BigDecimal px = series == null ? null : closeAsOf(series, p.tradeDate());
                if (px != null) {
                    mv.merge(industryOf(held.getKey(), mapping), px.multiply(held.getValue()), BigDecimal::add);
                }
            }
            industryMvByDay.add(mv);
        }
        // 外部流按日合并（Rp 剔除口径与 TWR 同：外部流日初到账）
        Map<LocalDate, BigDecimal> flowsByDay = new HashMap<>();
        for (ExternalFlow f : replay.externalFlows()) {
            flowsByDay.merge(f.date(), f.amount(), BigDecimal::add);
        }
        List<AttributionCalculator.DailyRow> rows = new ArrayList<>();
        BigDecimal unmappedWeightSum = BigDecimal.ZERO;
        for (int i = 1; i < pts.size(); i++) {
            DailyPoint prev = pts.get(i - 1);
            DailyPoint cur = pts.get(i);
            LocalDate t = cur.tradeDate();
            BigDecimal benchPrev = previousClose(benchmark, t);
            BigDecimal benchCur = benchmark.get(t);
            if (benchCur == null || benchPrev == null || benchPrev.signum() <= 0
                    || prev.totalValue().signum() <= 0 || cur.totalValue().signum() <= 0) {
                continue;
            }
            // 前一日快照权重：wp_i=行业市值/V_{t-1}、cashWeight=现金/V_{t-1}；Rp_i=行业市值_t/_{t-1}−1
            Map<String, BigDecimal> wp = new LinkedHashMap<>();
            Map<String, BigDecimal> rp = new LinkedHashMap<>();
            for (var e : industryMvByDay.get(i - 1).entrySet()) {
                if (e.getValue().signum() <= 0) {
                    continue;
                }
                wp.put(e.getKey(), e.getValue().divide(prev.totalValue(), MC));
                BigDecimal curMv = industryMvByDay.get(i).getOrDefault(e.getKey(), BigDecimal.ZERO);
                rp.put(e.getKey(), curMv.divide(e.getValue(), MC).subtract(BigDecimal.ONE));
            }
            // 基准行业收益：缺日行业缺键（当日贡献静默跳过，残差留痕）
            Map<String, BigDecimal> rbi = new LinkedHashMap<>();
            for (var e : industryCloses.entrySet()) {
                BigDecimal p = previousClose(e.getValue(), t);
                BigDecimal c = e.getValue().get(t);
                if (c != null && p != null && p.signum() > 0) {
                    rbi.put(e.getKey(), c.divide(p, MC).subtract(BigDecimal.ONE));
                }
            }
            BigDecimal f = flowsByDay.getOrDefault(t, BigDecimal.ZERO);
            rows.add(new AttributionCalculator.DailyRow(t, wp, benchmarkWeights, rp, rbi,
                    cur.totalValue().subtract(f).divide(prev.totalValue(), MC).subtract(BigDecimal.ONE),
                    benchCur.divide(benchPrev, MC).subtract(BigDecimal.ONE),
                    prev.cashBalance().divide(prev.totalValue(), MC),
                    rfDaily(rfPercent, t)));
            unmappedWeightSum = unmappedWeightSum.add(
                    wp.getOrDefault(BenchmarkIndustryWeightPort.UNMAPPED_KEY, BigDecimal.ZERO), MC);
        }
        BigDecimal unmapped = rows.isEmpty() ? BigDecimal.ZERO
                : unmappedWeightSum.divide(BigDecimal.valueOf(rows.size()), MC).setScale(10, RoundingMode.HALF_UP);
        return new AttributionInputs(rows, unmapped);
    }

    /** 个股→行业码（无映射归 UNMAPPED 桶，与基准权重端口同键）。 */
    private static String industryOf(String stockCode, Map<String, IndustryMappingPort.IndustryRef> mapping) {
        IndustryMappingPort.IndustryRef ref = mapping.get(stockCode);
        return ref != null ? ref.industryCode() : BenchmarkIndustryWeightPort.UNMAPPED_KEY;
    }

    /** 序列中 ≤ day 的最后一笔收盘（个股 close 缺失 forward-fill）；无 → null。 */
    private static BigDecimal closeAsOf(SortedMap<LocalDate, BigDecimal> series, LocalDate day) {
        SortedMap<LocalDate, BigDecimal> head = series.headMap(day.plusDays(1));
        return head.isEmpty() ? null : head.get(head.lastKey());
    }

    /** 序列中严格早于 day 的最后一笔收盘（指数相邻日推的前值）；无 → null。 */
    private static BigDecimal previousClose(SortedMap<LocalDate, BigDecimal> series, LocalDate day) {
        SortedMap<LocalDate, BigDecimal> head = series.headMap(day);
        return head.isEmpty() ? null : head.get(head.lastKey());
    }

    /** 1Y 国债百分数 → 日频小数（÷100÷365；缺失日 forward-fill，全缺=0）。 */
    private static BigDecimal rfDaily(SortedMap<LocalDate, BigDecimal> rfPercent, LocalDate day) {
        SortedMap<LocalDate, BigDecimal> head = rfPercent.headMap(day.plusDays(1));
        if (head.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return head.get(head.lastKey()).divide(HUNDRED, MC).divide(DAYS_PER_YEAR, MC);
    }

    /** 指数序列 → 日收益序列（与 twrIndex 同窗口，跳过指数未动日）。 */
    private static List<DatedReturn> dailyReturns(List<DatedIndex> idx) {
        List<DatedReturn> out = new ArrayList<>();
        for (int i = 1; i < idx.size(); i++) {
            BigDecimal prev = idx.get(i - 1).index();
            if (prev.signum() > 0) {
                out.add(new DatedReturn(idx.get(i).date(),
                        idx.get(i).index().divide(prev, java.math.MathContext.DECIMAL64)
                                .subtract(BigDecimal.ONE)));
            }
        }
        return out;
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
                    buys.add(new TradeStatsCalculator.BuyLot(pos.stockCode(), t.tradeDate(), t.quantity()));
                } else {
                    BigDecimal realizedBefore = agg.realizedPnl();
                    agg = agg.applySell(t.price(), t.quantity(), fee);
                    sells.add(new TradeStatsCalculator.SellLot(pos.stockCode(), t.tradeDate(), t.quantity(),
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

package com.portfolio.invest.application.allocation;

import com.portfolio.invest.domain.allocation.AllocationErrorCode;
import com.portfolio.invest.domain.allocation.AllocationException;
import com.portfolio.invest.domain.allocation.AllocationPlan;
import com.portfolio.invest.domain.allocation.AllocationPlanRepository;
import com.portfolio.invest.domain.allocation.AllocationTemplate;
import com.portfolio.invest.domain.allocation.AssetClass;
import com.portfolio.invest.domain.allocation.BacktestEngine;
import com.portfolio.invest.domain.analytics.DatedIndex;
import com.portfolio.invest.domain.analytics.DatedReturn;
import com.portfolio.invest.domain.analytics.IndexClosePort;
import com.portfolio.invest.domain.analytics.RiskFreeRatePort;
import com.portfolio.invest.domain.analytics.RiskMetricsCalculator;
import com.portfolio.invest.domain.analytics.TwrCalculator;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * 配置回测编排（MS-13 M07-F06）：方案权重 × 四类资产历史日收益 → BacktestEngine 份额制重放 →
 * analytics 指标计算器（application 层跨域引用先例）→ 只读 View。零落库、方法不加事务（读侧惯例）。
 */
@Service
public class BacktestApplicationService {

    /** 回测价格资产 → 指数代码（与 collector INDEX_CLOSE_CODES 同源；CASH 走 rf；REITS 无数据源）。 */
    private static final Map<AssetClass, String> PRICE_CODES = Map.of(
            AssetClass.STOCK, "000300", AssetClass.BOND, "H11001", AssetClass.GOLD, "518880");

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365);

    private final AllocationPlanRepository repository;
    private final IndexClosePort indexClose;
    private final RiskFreeRatePort riskFree;

    public BacktestApplicationService(AllocationPlanRepository repository,
                                      IndexClosePort indexClose, RiskFreeRatePort riskFree) {
        this.repository = repository;
        this.indexClose = indexClose;
        this.riskFree = riskFree;
    }

    /**
     * 配置回测：权重来源优先级 planId > template > 激活方案；window=3Y/5Y/MAX（null/未知值按 5Y），
     * MAX 自 2006-01-01（treasury 曲线最早）起，实际窗口由资产日期交集截齐——View 的
     * windowStart/windowEnd 反映实际、window/rebalance 回显请求值。
     */
    public BacktestView backtest(Long userId, Long planId, String template, String window, String rebalance) {
        PlanRef source = resolvePlan(userId, planId, template);
        Map<AssetClass, BigDecimal> weights = source.weights();
        // 引擎对 REITS>0 抛 REITS_BACKTEST_UNSUPPORTED（422）；编排侧先拦——纯 REITs 方案若放行到
        // 取数阶段会被误报为数据不足，且混合方案可省 2~3 次端口调用
        if (weights.getOrDefault(AssetClass.REITS, BigDecimal.ZERO).signum() > 0) {
            throw new AllocationException(AllocationErrorCode.REITS_BACKTEST_UNSUPPORTED,
                    "REITs 无回测数据源，请将 REITs 权重置 0 后重试");
        }
        LocalDate to = LocalDate.now();
        LocalDate from = windowFrom(window, to);
        // rf 一次取数两处用：CASH 日收益 + 夏普超额（端口契约空表→空 map，勿传 null）
        SortedMap<LocalDate, BigDecimal> rfPercent = riskFree.oneYearSeries(from, to);
        Map<AssetClass, List<BacktestEngine.DatedReturn>> rets = assetReturns(weights, rfPercent, from, to);
        int everyN = switch (rebalance == null ? "never" : rebalance) {
            case "quarterly" -> BacktestEngine.RebalanceMode.QUARTERLY.tradingDays();
            case "annual" -> BacktestEngine.RebalanceMode.ANNUAL.tradingDays();
            default -> 0;
        };
        BacktestEngine.BacktestCurve curve = BacktestEngine.run(weights, rets, everyN);
        // 指标：曲线点转 analytics 口径（DatedIndex/DatedReturn 与 allocation 包同名类不同类，按 import 区分）
        List<BacktestEngine.CurvePoint> pts = curve.points();
        List<DatedIndex> index = new ArrayList<>(pts.size());
        for (BacktestEngine.CurvePoint p : pts) {
            index.add(new DatedIndex(p.date(), p.value()));
        }
        List<DatedReturn> daily = new ArrayList<>(pts.size() - 1);
        for (int i = 1; i < pts.size(); i++) {
            daily.add(new DatedReturn(pts.get(i).date(),
                    pts.get(i).value().divide(pts.get(i - 1).value(), MC).subtract(BigDecimal.ONE)));
        }
        RiskMetricsCalculator.MddResult mdd = RiskMetricsCalculator.maxDrawdown(index);
        RiskMetricsCalculator.SharpeResult sharpe = RiskMetricsCalculator.sharpe(daily, rfPercent);
        // 曲线无外部现金流：TWR = last/first − 1；年化复用 TwrCalculator（365/天数 复利）
        BigDecimal total = pts.get(pts.size() - 1).value()
                .divide(pts.get(0).value(), MathContext.DECIMAL64).subtract(BigDecimal.ONE);
        long days = ChronoUnit.DAYS.between(pts.get(0).date(), pts.get(pts.size() - 1).date());
        BigDecimal annualized = TwrCalculator.annualized(total, days);
        List<CurvePointView> curveView = pts.stream()
                .map(p -> new CurvePointView(p.date().toString(), p.value().toPlainString()))
                .toList();
        return new BacktestView(source.name(),
                pts.get(0).date().toString(), pts.get(pts.size() - 1).date().toString(),
                window == null ? "5Y" : window, rebalance == null ? "never" : rebalance,
                curveView, plain(annualized), plain(mdd.mdd()), plain(sharpe.value()),
                sharpe.rfFallback());
    }

    /** 权重来源解析：planId（缺/非本人→NOT_FOUND）> template（非法值→INVALID_INPUT）> 激活方案（无→NO_ACTIVE_PLAN）。 */
    private PlanRef resolvePlan(Long userId, Long planId, String template) {
        if (planId != null) {
            AllocationPlan plan = repository.findByIdAndUserId(planId, userId)
                    .orElseThrow(() -> new AllocationException(AllocationErrorCode.NOT_FOUND, "方案不存在"));
            return new PlanRef(plan.name(), plan.weights());
        }
        if (template != null) {
            AllocationTemplate t;
            try {
                t = AllocationTemplate.valueOf(template);
            } catch (IllegalArgumentException e) {
                throw new AllocationException(AllocationErrorCode.INVALID_INPUT, "未知配置模板: " + template);
            }
            return new PlanRef(t.displayName(), t.weights());
        }
        AllocationPlan active = repository.findActiveByUserId(userId)
                .orElseThrow(() -> new AllocationException(AllocationErrorCode.NO_ACTIVE_PLAN,
                        "无可用方案：请指定 planId/template 或先激活一个方案"));
        return new PlanRef(active.name(), active.weights());
    }

    /** 请求窗口 → 起始日（MAX=数据最早可得起点 2006-01-01，实际截齐由交集决定）。 */
    private static LocalDate windowFrom(String window, LocalDate to) {
        return switch (window == null ? "5Y" : window) {
            case "3Y" -> to.minusYears(3);
            case "MAX" -> LocalDate.of(2006, 1, 1);
            default -> to.minusYears(5);
        };
    }

    /**
     * 四类资产日收益组装：价格资产（股/债/金，权重>0 才取数）收盘序列相邻日推
     * close_t/close_{t−1}−1，序列 &lt;2 点 → INVALID_INPUT（窗口内无从相邻推）；CASH=rf 逐日
     * （1Y 百分数 ÷100÷365，缺失日 forward-fill、区间起点前无值按 0）——CASH 序列日期取
     * 「价格资产收益日期 ∪ rf 序列日期」口径：不收缩引擎日期交集（价格日期全被覆盖），纯现金
     * 方案也能以 rf 日期独立成曲线。各资产日期交集为空 → INVALID_INPUT（引擎对空交集抛
     * NoSuchElementException，编排侧先拦）。
     */
    private Map<AssetClass, List<BacktestEngine.DatedReturn>> assetReturns(
            Map<AssetClass, BigDecimal> weights, SortedMap<LocalDate, BigDecimal> rfPercent,
            LocalDate from, LocalDate to) {
        Map<AssetClass, List<BacktestEngine.DatedReturn>> rets = new LinkedHashMap<>();
        TreeSet<LocalDate> cashDates = new TreeSet<>(rfPercent.keySet());
        for (var e : PRICE_CODES.entrySet()) {
            if (weights.getOrDefault(e.getKey(), BigDecimal.ZERO).signum() <= 0) {
                continue;
            }
            SortedMap<LocalDate, BigDecimal> closes = indexClose.closes(e.getValue(), from, to);
            if (closes == null || closes.size() < 2) {
                throw new AllocationException(AllocationErrorCode.INVALID_INPUT,
                        e.getKey().label() + "（" + e.getValue() + "）窗口内收盘不足 2 点，无法回测");
            }
            List<BacktestEngine.DatedReturn> series = new ArrayList<>(closes.size() - 1);
            BigDecimal prev = null;
            for (var c : closes.entrySet()) {
                if (prev != null) {
                    series.add(new BacktestEngine.DatedReturn(c.getKey(),
                            c.getValue().divide(prev, MC).subtract(BigDecimal.ONE)));
                }
                prev = c.getValue();
            }
            rets.put(e.getKey(), series);
            for (BacktestEngine.DatedReturn r : series) {
                cashDates.add(r.date());
            }
        }
        if (weights.getOrDefault(AssetClass.CASH, BigDecimal.ZERO).signum() > 0) {
            List<BacktestEngine.DatedReturn> cash = new ArrayList<>(cashDates.size());
            for (LocalDate d : cashDates) {
                cash.add(new BacktestEngine.DatedReturn(d, rfDaily(rfPercent, d)));
            }
            rets.put(AssetClass.CASH, cash);
        }
        // 日期交集空守卫（REITS 已前置剔除、Σ=100 保证至少一类正权重，rets 非空）
        TreeSet<LocalDate> intersection = null;
        for (List<BacktestEngine.DatedReturn> s : rets.values()) {
            TreeSet<LocalDate> ds = new TreeSet<>(s.stream().map(BacktestEngine.DatedReturn::date).toList());
            if (intersection == null) {
                intersection = ds;
            } else {
                intersection.retainAll(ds);
            }
        }
        if (intersection == null || intersection.isEmpty()) {
            throw new AllocationException(AllocationErrorCode.INVALID_INPUT, "回测资产日期无交集，数据不足");
        }
        return rets;
    }

    /** 1Y 国债百分数 → 日频小数（÷100÷365；缺失日 forward-fill，区间起点前无值=0）。 */
    private static BigDecimal rfDaily(SortedMap<LocalDate, BigDecimal> rfPercent, LocalDate day) {
        SortedMap<LocalDate, BigDecimal> head = rfPercent.headMap(day.plusDays(1));
        if (head.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return head.get(head.lastKey()).divide(HUNDRED, MC).divide(DAYS_PER_YEAR, MC);
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    /** 权重来源（方案名/模板显示名 + 目标权重）。 */
    private record PlanRef(String name, Map<AssetClass, BigDecimal> weights) {}
}

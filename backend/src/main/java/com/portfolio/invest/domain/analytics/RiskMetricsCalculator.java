package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 风险指标（MS-13 F07/F08）：TWR 净值指数、最大回撤；夏普/Calmar 见同类后续方法，spec 02-设计规格 §2.1/§2.2。 */
public final class RiskMetricsCalculator {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private RiskMetricsCalculator() {}

    /** 日链接累积指数（首日=1），R_t 与 TwrCalculator 日子周期同口径（外部现金流日初到账剔除）。 */
    public static List<DatedIndex> twrIndex(NavSeries series, List<ExternalFlow> flows) {
        List<DatedIndex> out = new ArrayList<>();
        if (series.points().isEmpty()) {
            return out;
        }
        Map<LocalDate, BigDecimal> byDay = new TreeMap<>();
        for (ExternalFlow f : flows) {
            byDay.merge(f.date(), f.amount(), BigDecimal::add);
        }
        BigDecimal idx = BigDecimal.ONE;
        out.add(new DatedIndex(series.points().get(0).tradeDate(), BigDecimal.ONE));
        for (int i = 1; i < series.points().size(); i++) {
            DailyPoint prev = series.points().get(i - 1);
            DailyPoint cur = series.points().get(i);
            BigDecimal f = byDay.getOrDefault(cur.tradeDate(), BigDecimal.ZERO);
            if (prev.totalValue().signum() > 0) {
                BigDecimal r = cur.totalValue().subtract(f)
                        .divide(prev.totalValue(), MC)
                        .subtract(BigDecimal.ONE);
                idx = idx.multiply(BigDecimal.ONE.add(r), MC);
            }
            out.add(new DatedIndex(cur.tradeDate(), idx));
        }
        return out;
    }

    /** 最大回撤（两遍扫描）：一遍找最深谷，二遍找恢复日；<2 点全空。 */
    public static MddResult maxDrawdown(List<DatedIndex> index) {
        if (index.size() < 2) {
            return new MddResult(null, null, null, null, null);
        }
        BigDecimal peak = index.get(0).index();
        LocalDate peakDate = index.get(0).date();
        BigDecimal peakAtMax = peak;
        LocalDate maxPeakDate = peakDate, maxTroughDate = null;
        BigDecimal mdd = BigDecimal.ZERO;
        for (DatedIndex p : index.subList(1, index.size())) {
            if (p.index().compareTo(peak) > 0) {
                peak = p.index();
                peakDate = p.date();
                continue;
            }
            BigDecimal dd = BigDecimal.ONE.subtract(p.index().divide(peak, MC));
            if (dd.compareTo(mdd) > 0) {
                mdd = dd;
                peakAtMax = peak;
                maxPeakDate = peakDate;
                maxTroughDate = p.date();
            }
        }
        LocalDate recovery = null;
        if (mdd.signum() > 0 && maxTroughDate != null) {
            for (DatedIndex p : index) {
                if (p.date().isAfter(maxTroughDate) && p.index().compareTo(peakAtMax) >= 0) {
                    recovery = p.date();
                    break;
                }
            }
        }
        DatedIndex last = index.get(index.size() - 1);
        BigDecimal runningPeak = BigDecimal.ZERO;
        for (DatedIndex p : index) {
            if (p.index().compareTo(runningPeak) > 0) {
                runningPeak = p.index();
            }
        }
        BigDecimal current = last.index().compareTo(runningPeak) >= 0
                ? BigDecimal.ZERO
                : BigDecimal.ONE.subtract(last.index().divide(runningPeak, MC));
        return new MddResult(mdd.setScale(10, RoundingMode.HALF_UP), maxPeakDate, maxTroughDate,
                recovery, current.setScale(10, RoundingMode.HALF_UP));
    }

    /** 回撤结果：mdd/当前回撤为小数（0.25=25%）；<2 点全 null；recoveryDate null=进行中。 */
    public record MddResult(BigDecimal mdd, LocalDate peakDate, LocalDate troughDate,
                            LocalDate recoveryDate, BigDecimal currentDrawdown) {}
}

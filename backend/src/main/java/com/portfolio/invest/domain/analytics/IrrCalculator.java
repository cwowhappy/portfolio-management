package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** XIRR：ACT/365 贴现、二分求根（spec 02-design §2.3）。 */
public final class IrrCalculator {

    private static final MathContext MC = new MathContext(24, RoundingMode.HALF_UP);
    private static final BigDecimal LOW = new BigDecimal("-0.999");
    private static final BigDecimal HIGH = BigDecimal.TEN;
    private static final BigDecimal TOL = new BigDecimal("0.000000001");
    private static final int MAX_ITER = 200;

    private IrrCalculator() {}

    /** ACT/365 贴现 NPV=0 求根；二分区间 [−0.999,10]，容忍 1e-9，上限 200 轮。
     *  流数 <2 或无变号（无解/恒正）→ Optional.empty()。 */
    public static Optional<BigDecimal> xirr(List<DatedAmount> flows) {
        if (flows.size() < 2) {
            return Optional.empty();
        }
        LocalDate t0 = flows.stream().map(DatedAmount::date).min(LocalDate::compareTo).orElseThrow();
        BigDecimal lo = LOW, hi = HIGH;
        BigDecimal fLo = npv(flows, t0, lo), fHi = npv(flows, t0, hi);
        if (fLo.multiply(fHi).signum() > 0) {
            return Optional.empty();  // 无变号（无解）
        }
        for (int i = 0; i < MAX_ITER && hi.subtract(lo).compareTo(TOL) > 0; i++) {
            BigDecimal mid = lo.add(hi).divide(BigDecimal.valueOf(2), MC);
            BigDecimal fMid = npv(flows, t0, mid);
            if (fMid.signum() == 0) {
                return Optional.of(mid.setScale(10, RoundingMode.HALF_UP));
            }
            if (fLo.multiply(fMid).signum() < 0) {
                hi = mid;
            } else {
                lo = mid;
                fLo = fMid;
            }
        }
        return Optional.of(lo.add(hi).divide(BigDecimal.valueOf(2), MC).setScale(10, RoundingMode.HALF_UP));
    }

    private static BigDecimal npv(List<DatedAmount> flows, LocalDate t0, BigDecimal r) {
        BigDecimal sum = BigDecimal.ZERO;
        for (DatedAmount f : flows) {
            double years = ChronoUnit.DAYS.between(t0, f.date()) / 365.0;
            double factor = Math.pow(r.add(BigDecimal.ONE).doubleValue(), years);
            sum = sum.add(f.amount().divide(BigDecimal.valueOf(factor), MC), MC);
        }
        return sum;
    }
}

package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 日频子周期 Brinson 累加（MS-13 F09，spec 02-设计规格 §2.4）：对沪深300 超额拆
 * 配置贡献 (wp−wb)(Rb_i−Rb) + 选择贡献 wp(Rp_i−Rb_i)（交互并入选择，Brinson-Fachler 变体），
 * 现金特殊单元全记配置项 wc(R_cash−Rb)。totalExcess=Σ_t(Rp−Rb)（算术累计），
 * residual=totalExcess−Σ贡献（日频权重近似的数值损耗，显式留痕不隐藏）。
 */
public final class AttributionCalculator {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private AttributionCalculator() {}

    /** 单日归因输入：权重/收益均小数；portfolioIndustryReturns 缺键=组合当日无该行业持仓。 */
    public record DailyRow(LocalDate tradeDate,
                           Map<String, BigDecimal> portfolioIndustryWeights,
                           Map<String, BigDecimal> benchmarkIndustryWeights,
                           Map<String, BigDecimal> portfolioIndustryReturns,
                           Map<String, BigDecimal> benchmarkIndustryReturns,
                           BigDecimal portfolioReturn, BigDecimal benchmarkReturn,
                           BigDecimal cashWeight, BigDecimal rfDaily) {}

    public record IndustryEffect(String industry, BigDecimal allocation, BigDecimal selection) {}

    public record AttributionResult(List<IndustryEffect> rows, String unmappedIndustryKey,
                                    BigDecimal cashAllocation, BigDecimal totalExcess,
                                    BigDecimal residual, LocalDate windowStart, LocalDate windowEnd) {}

    public static AttributionResult attribute(List<DailyRow> rows) {
        TreeSet<String> industries = new TreeSet<>();
        rows.forEach(r -> {
            industries.addAll(r.portfolioIndustryWeights().keySet());
            industries.addAll(r.benchmarkIndustryWeights().keySet());
        });
        Map<String, BigDecimal> alloc = new LinkedHashMap<>();
        Map<String, BigDecimal> sel = new LinkedHashMap<>();
        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal totalExcess = BigDecimal.ZERO;
        for (String i : industries) {
            alloc.put(i, BigDecimal.ZERO);
            sel.put(i, BigDecimal.ZERO);
        }
        for (DailyRow r : rows) {
            BigDecimal rb = r.benchmarkReturn();
            totalExcess = totalExcess.add(r.portfolioReturn().subtract(rb), MC);
            for (String i : industries) {
                BigDecimal wp = r.portfolioIndustryWeights().getOrDefault(i, BigDecimal.ZERO);
                BigDecimal wb = r.benchmarkIndustryWeights().getOrDefault(i, BigDecimal.ZERO);
                BigDecimal rbi = r.benchmarkIndustryReturns().get(i);
                if (rbi != null) {
                    alloc.merge(i, wp.subtract(wb, MC).multiply(rbi.subtract(rb), MC), BigDecimal::add);
                }
                BigDecimal rpi = r.portfolioIndustryReturns().get(i);
                if (rpi != null && rbi != null) {
                    sel.merge(i, wp.multiply(rpi.subtract(rbi), MC), BigDecimal::add);
                }
            }
            // 现金特殊单元：收益=rf，全记配置贡献，无选择项
            cash = cash.add(r.cashWeight().multiply(r.rfDaily().subtract(rb), MC), MC);
        }
        BigDecimal attributed = cash;
        for (String i : industries) {
            attributed = attributed.add(alloc.get(i)).add(sel.get(i), MC);
        }
        List<IndustryEffect> out = new ArrayList<>();
        for (String i : industries) {
            out.add(new IndustryEffect(i, scale(alloc.get(i)), scale(sel.get(i))));
        }
        return new AttributionResult(out, "UNMAPPED", scale(cash), scale(totalExcess),
                scale(totalExcess.subtract(attributed, MC)),
                rows.isEmpty() ? null : rows.get(0).tradeDate(),
                rows.isEmpty() ? null : rows.get(rows.size() - 1).tradeDate());
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(10, RoundingMode.HALF_UP);
    }
}

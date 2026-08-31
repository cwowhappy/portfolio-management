package com.portfolio.invest.domain.allocation;

import java.math.BigDecimal;
import java.util.Map;

/** 内置经典配置模板：名称 + 目标权重（百分比，和为 100）。 */
public enum AllocationTemplate {

    BALANCED_60_40("60/40 股债平衡", Map.of(
            AssetClass.STOCK, w("60"), AssetClass.BOND, w("40"))),
    PERMANENT_PORTFOLIO("永久组合", Map.of(
            AssetClass.STOCK, w("25"), AssetClass.BOND, w("25"),
            AssetClass.GOLD, w("25"), AssetClass.CASH, w("25"))),
    ALL_WEATHER("全天候", Map.of(
            AssetClass.STOCK, w("30"), AssetClass.BOND, w("55"), AssetClass.GOLD, w("15"))),
    BUFFETT_90_10("巴菲特 90/10", Map.of(
            AssetClass.STOCK, w("90"), AssetClass.CASH, w("10")));

    private final String displayName;
    private final Map<AssetClass, BigDecimal> weights;

    AllocationTemplate(String displayName, Map<AssetClass, BigDecimal> weights) {
        this.displayName = displayName;
        this.weights = Map.copyOf(weights);
    }

    /** 注意不能用 name()：枚举的 name() 是 Enum 的 final 方法（返回常量名）。 */
    public String displayName() { return displayName; }
    public Map<AssetClass, BigDecimal> weights() { return weights; }

    private static BigDecimal w(String v) { return new BigDecimal(v); }
}

package com.portfolio.invest.domain.wiki;

/**
 * 原则纪律指标（三期 MS-15 预警消费同一枚举——稳定契约）。
 * 单位语义固化在枚举：ratio 类阈值区间 (0,1]，倍数类阈值 > 0。
 */
public enum PrincipleMetric {
    SINGLE_POSITION_RATIO(true, "单票仓位上限"),
    INDUSTRY_POSITION_RATIO(true, "单行业仓位上限"),
    STOCK_PE_MAX(false, "个股PE上限"),
    STOCK_PB_MAX(false, "个股PB上限");

    private final boolean ratio;
    private final String label;

    PrincipleMetric(boolean ratio, String label) {
        this.ratio = ratio;
        this.label = label;
    }

    public boolean isRatio() { return ratio; }
    public String label() { return label; }
}

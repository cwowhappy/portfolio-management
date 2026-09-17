package com.portfolio.invest.domain.industry;

import java.math.BigDecimal;

/**
 * 景气度三档标注（M10-F05）：ROE 趋势（近4季均值−前4季均值，pp）与营收增速（最新报告期，%）双输入。
 * 上行=且（两条件同时达门槛，防单指标噪声）；下行=或（更敏感，「且/或」不对称为有意设计）。任一输入缺失返回 null（不标注）。
 */
public enum Prosperity {
    UP, FLAT, DOWN;

    static final BigDecimal ROE_DELTA_UP = new BigDecimal("1");
    static final BigDecimal ROE_DELTA_DOWN = new BigDecimal("-1");
    static final BigDecimal REVENUE_UP = new BigDecimal("10");
    static final BigDecimal REVENUE_DOWN = new BigDecimal("-10");

    public static Prosperity of(BigDecimal roeDelta, BigDecimal revenueYoy) {
        if (roeDelta == null || revenueYoy == null) {
            return null;
        }
        if (roeDelta.compareTo(ROE_DELTA_UP) >= 0 && revenueYoy.compareTo(REVENUE_UP) >= 0) {
            return UP;
        }
        if (roeDelta.compareTo(ROE_DELTA_DOWN) <= 0 || revenueYoy.compareTo(REVENUE_DOWN) <= 0) {
            return DOWN;
        }
        return FLAT;
    }
}

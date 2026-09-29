package com.portfolio.invest.domain.intelligence;

import java.math.BigDecimal;
import java.util.List;

/**
 * 业绩公告六字段要点契约（F07/决策 #11，设计规格 §4.4；metrics JSONB 列的存储形状）。
 *
 * <p>五个数值字段均为可空 BigDecimal——<b>未披露字段为 null 且字段名进 undisclosed 列表，
 * 严禁编造数值</b>（LLM 抽取提示词与落库同守此契约）。口径：Yi 后缀=亿元、Pct 后缀=百分比。
 * JSON 键名即组件名（Jackson 直序列化）：
 * {@code {"revenueYi":128.56,"netProfitYi":31.20,"netProfitYoyPct":25.30,
 * "deductedProfitYi":30.05,"grossMarginPct":null,"dividendDesc":"每10股派2元",
 * "undisclosed":["毛利率"]}}。
 *
 * @param revenueYi        营业收入（亿元）
 * @param netProfitYi      归母净利润（亿元）
 * @param netProfitYoyPct  归母净利润同比（%）
 * @param deductedProfitYi 扣非净利润（亿元）
 * @param grossMarginPct   毛利率（%）
 * @param dividendDesc     分红描述（如「每10股派2元」）
 * @param undisclosed      未披露字段名列表（与上述 null 字段对应；null 归一为空列表）
 */
public record AnnouncementMetrics(
        BigDecimal revenueYi,
        BigDecimal netProfitYi,
        BigDecimal netProfitYoyPct,
        BigDecimal deductedProfitYi,
        BigDecimal grossMarginPct,
        String dividendDesc,
        List<String> undisclosed) {

    public AnnouncementMetrics {
        undisclosed = undisclosed == null ? List.of() : List.copyOf(undisclosed);
    }
}

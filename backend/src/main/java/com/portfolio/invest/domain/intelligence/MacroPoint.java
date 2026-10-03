package com.portfolio.invest.domain.intelligence;

import java.math.BigDecimal;

/**
 * 宏观指标单期观测点（intelligence_macro_series 行，F13 决策 #12 五先行指标）。
 *
 * <p>period 格式与 periodType 对应：MONTH={@code YYYY-MM}（CPI/PPI/PMI/LPR/AFMI 月度）、
 * DAY={@code YYYY-MM-DD}（国债收益率合成点）——同指标格式恒定，字典序即时间序
 * （仓库 ORDER BY period 的依据）。该表由 collector 采集写入（跨服务契约，V3 注释口径），
 * backend 只读。
 *
 * @param indicator   指标码（CPI/PPI/PMI/LPR/AFMI，或 {@link #INDICATOR_TY1Y}/{@link #INDICATOR_TY10Y}）
 * @param period      期别（月度 2026-09 / 日度 2026-09-29）
 * @param periodType  期别类型（MONTH / DAY，对应 V3 ck_macro_period_type 约束）
 * @param value       指标值（CPI 同比 %、LPR % 等，NUMERIC(18,4)）
 * @param yoy         同比（可空——源未给出时缺席而非编造）
 * @param sourceUrl   源链接（可空）
 * @param sourceNote  源说明（可空，如「东方财富-中国月度CPI同比」）
 */
public record MacroPoint(
        String indicator,
        String period,
        String periodType,
        BigDecimal value,
        BigDecimal yoy,
        String sourceUrl,
        String sourceNote) {

    /**
     * 国债收益率合成指标码：1 年期。**复用既有采集任务写入的 treasury_yield_curve
     * （V1 跨服务契约表）不另采**——宏观序列不建国债行（V3 表注释口径），由仓库
     * {@link MacroRepository#findLatestPerIndicator()} 跨表只读合成（T6 宏观工具与
     * P4 宏观页共用此常量，勿在调用侧硬编码字符串）。
     */
    public static final String INDICATOR_TY1Y = "TY1Y";

    /** 国债收益率合成指标码：10 年期（口径同 {@link #INDICATOR_TY1Y}）。 */
    public static final String INDICATOR_TY10Y = "TY10Y";
}

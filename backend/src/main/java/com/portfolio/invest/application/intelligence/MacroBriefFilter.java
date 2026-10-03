package com.portfolio.invest.application.intelligence;

/**
 * 宏观简报过滤器（agent 工具与 P4 宏观页共用入参载体）：全部可空。
 *
 * <p>{@code indicators} 为指标码逗号分隔串（如 "CPI,PMI,TY1Y"），由
 * {@link IntelligenceQueryService#macroBrief} 统一 trim/大写/去重；空/空白归全七缺省
 * （五先行指标 CPI/PPI/PMI/LPR/AFMI + 国债收益率 TY1Y/TY10Y）。{@code policyDays}
 * 为政策事件回看天数（服务内夹紧 1..90，缺省 30）。
 *
 * @param indicators 指标码逗号分隔串（原始透传，服务内解析归一）
 * @param policyDays 政策回看天数（服务内夹紧 1..90，缺省 30）
 */
public record MacroBriefFilter(String indicators, Integer policyDays) {
}

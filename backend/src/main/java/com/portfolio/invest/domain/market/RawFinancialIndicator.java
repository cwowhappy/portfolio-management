package com.portfolio.invest.domain.market;

/**
 * 已规范化的单期财务指标中间结构（供应商无关）：由基础设施层把上游 wire format
 * （如东财 F10 数据中台 result.data[] 的 REPORT_DATE/EPSJB/BPS/TOTALOPERATEREVE/
 * PARENTNETPROFIT/ROEJQ/XSMLL 字段）映射为本结构后交给 {@link MarketDataParser}——
 * 字段码与 JSON 路径导航只存在 infrastructure（2026-10-15 审查 §五 L2 字段码下沉配套引入）。
 *
 * @param reportDate  报告期（原始文本，可带时间后缀——截断为 yyyy-MM-dd 的归一化由 domain 解析器承担）
 * @param eps         每股收益（源缺失/非数值归 null，不编造）
 * @param bps         每股净资产（可空）
 * @param revenue     营业总收入（可空）
 * @param profit      归母净利润（可空）
 * @param roe         加权净资产收益率（可空）
 * @param grossMargin 毛利率（可空）
 */
public record RawFinancialIndicator(
        String reportDate,
        Double eps,
        Double bps,
        Double revenue,
        Double profit,
        Double roe,
        Double grossMargin) {
}

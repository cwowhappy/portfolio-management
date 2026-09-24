package com.portfolio.invest.application.analytics;

import java.util.List;

/**
 * 归因结果（MS-13 F09）：日频子周期 Brinson 对沪深300 的配置/选择贡献拆解。
 * 数值 toPlainString（小数，累计贡献）；residual=totalExcess−Σ贡献（日频权重近似损耗，显式留痕）；
 * unmappedValueShare=未映射个股市值占比窗口均值（数据质量提示）；窗口=null 表示无可归因交易日。
 */
public record AttributionView(String windowStart, String windowEnd,
                              List<Row> rows, String cashAllocation, String totalExcess,
                              String residual, String unmappedValueShare) {

    /** industry=申万行业码（未映射桶为 "UNMAPPED"）；allocation/selection=行业累计贡献小数。 */
    public record Row(String industry, String industryName, String allocation, String selection) {}
}

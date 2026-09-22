package com.portfolio.invest.domain.market;

import java.util.ArrayList;
import java.util.List;

/**
 * 杜邦三因子拆解纯函数：ROE = 净利率 × 总资产周转率 × 权益乘数。
 * 入参口径：netMargin 为小数（0.25=25%）；roa/debtToAssets/roe 为库表百分数原值（5.0=5%、60.0=60%）。
 * 出参：净利率/周转率为小数、权益乘数为倍、roePercent 保持百分数；缺数据因子为 null 并记入 missingFactors（部分拆解仍可展示）。
 */
public final class DuPontAnalysis {

    private DuPontAnalysis() {}

    public record DuPontResult(Double netMargin, Double assetTurnover, Double equityMultiplier,
                               Double roePercent, String recordReportDate, String liveReportDate,
                               List<String> missingFactors) {}

    public static DuPontResult of(Double netMargin, Double roaPercent, Double debtToAssetsPercent,
                                  Double roePercent, String recordReportDate, String liveReportDate) {
        List<String> missing = new ArrayList<>();
        Double equityMultiplier = null;
        if (debtToAssetsPercent == null) {
            missing.add("资产负债率");
        } else if (debtToAssetsPercent >= 100.0) {
            missing.add("权益乘数(资产负债率≥100%)");
        } else {
            equityMultiplier = 1.0 / (1.0 - debtToAssetsPercent / 100.0);
        }
        Double assetTurnover = null;
        if (roaPercent == null) {
            missing.add("ROA");
        } else if (netMargin == null || netMargin == 0.0) {
            if (netMargin == null) missing.add("净利率");
            missing.add("总资产周转率(缺净利率)");
        } else {
            assetTurnover = (roaPercent / 100.0) / netMargin;
        }
        return new DuPontResult(netMargin, assetTurnover, equityMultiplier, roePercent,
                recordReportDate, liveReportDate, List.copyOf(missing));
    }
}

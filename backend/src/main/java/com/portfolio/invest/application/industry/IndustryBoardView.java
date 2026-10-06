package com.portfolio.invest.application.industry;

import com.portfolio.invest.domain.industry.Prosperity;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 行业板面读模型（Task 8 契约）：估值 + 5 年窗口分位 + 景气标注。 */
public record IndustryBoardView(String industryCode, String industryName,
        BigDecimal pe, BigDecimal pb, BigDecimal roe, BigDecimal dividendYield,
        BigDecimal pePercentile, BigDecimal pbPercentile,
        Prosperity prosperity, ProsperityInputs prosperityInputs,
        /** 底层估值快照交易日（MS-29 B4 时点透出；表/摘要列不新增）。 */
        LocalDate tradingDay) {

    /** 景气原始输入：快照存在即透出（中位数可缺失），供前端提示「样本不足」等场景。 */
    public record ProsperityInputs(BigDecimal roeDeltaMedian, BigDecimal revenueYoyMedian, long sampleSize) {}
}

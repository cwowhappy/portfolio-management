package com.portfolio.invest.application.valuation;

import com.portfolio.invest.domain.valuation.IndexValuation;
import com.portfolio.invest.domain.valuation.TreasuryYield;
import com.portfolio.invest.domain.valuation.ValuationSnapshot;

import java.util.List;

/** 历史序列视图（FR-B6 走势图）：直接复用领域读模型 record。
 *  indexValuations 为全部 5 个指数序列拼接（上证50/沪深300/中证500/创业板指/科创50），每点自带 indexCode/indexName。 */
public record ValuationHistoryView(
        List<ValuationSnapshot> snapshots,
        List<TreasuryYield> treasuryYields,
        List<IndexValuation> indexValuations
) {}

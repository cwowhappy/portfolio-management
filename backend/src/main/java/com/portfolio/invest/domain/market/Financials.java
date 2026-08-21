package com.portfolio.invest.domain.market;

import java.util.List;

/** 财务指标序列 + 当前估值。 */
public record Financials(
        String code,
        String name,
        Double pe,
        Double pb,
        List<FinancialIndicator> indicators) {}

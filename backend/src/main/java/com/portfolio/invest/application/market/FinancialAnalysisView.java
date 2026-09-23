package com.portfolio.invest.application.market;

import com.portfolio.invest.domain.market.DuPontAnalysis;
import com.portfolio.invest.domain.market.FinancialRecord;
import com.portfolio.invest.domain.market.Financials;
import java.util.List;

/** 财报分析读模型：库表季序列 + 东财 live + 杜邦拆解（库表空则 duPont=null）。 */
public record FinancialAnalysisView(List<FinancialRecord> records, Financials live,
                                    DuPontAnalysis.DuPontResult duPont) {}

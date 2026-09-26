package com.portfolio.invest.application.industry;

import java.math.BigDecimal;
import java.util.List;

/**
 * 行业全景卡视图（F06，设计规格 §七）：库内派生——上市口径复用下钻页成员表同源查询
 * （findIndustryStocks total_mv/DESC/1000），策展与融资热度查 V19 两表。coverageNote
 * 为 API 口径字段（需求 NFR #1 口径诚实）；roundDistribution 按 FundingRound.order()
 * 排序，零计数轮次不透出。
 */
public record UnlistedOverviewView(int listedCount, BigDecimal listedMarketCapYi, int curatedCount,
                                   int fundingEvents12m, List<RoundCount> roundDistribution,
                                   String coverageNote) {

    /** 口径标注常量（页面与 API 同源，需求 §三A）。 */
    public static final String COVERAGE_NOTE = "策展名单与月度摘录融资事件，非全量口径";

    public UnlistedOverviewView {
        roundDistribution = List.copyOf(roundDistribution);
    }

    /** 轮次分布项：round 为 FundingRound 枚举名（前端按枚举序渲染）。 */
    public record RoundCount(String round, int count) {}
}

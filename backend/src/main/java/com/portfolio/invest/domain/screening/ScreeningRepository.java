package com.portfolio.invest.domain.screening;

import java.util.List;

public interface ScreeningRepository {
    List<StockScreeningResult> findStocks(ScreeningCriteria criteria);

    /**
     * ETF 四维筛选（etf_basic 周更目录，无快照日维度）。
     * null 语义：「未知值不命中数值条件」——fee_rate/scale/tracking_error_1y 为 null 的行
     * 在 SQL 数值比较下自然过滤；tracking_error_1y null 行排序置最后（NULLS LAST）。
     */
    List<FundScreeningResult> findFunds(FundScreeningCriteria criteria);

    /** 按代码集取最新快照日全维度行（自选列表/添加校验用）；空集返回空列表。 */
    List<StockScreeningResult> findStocksByCodes(List<String> codes);

    /** etf_basic 目录存在性（自选添加校验：ETF 代码不在股票快照时按此放行）。 */
    boolean existsFund(String fundCode);

    /** 最新快照日代码前缀/名称包含搜索（自选搜索框候选）。 */
    List<StockSearchHit> searchLatestSnapshot(String keyword, int limit);
}

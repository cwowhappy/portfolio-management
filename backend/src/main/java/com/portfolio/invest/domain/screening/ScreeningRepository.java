package com.portfolio.invest.domain.screening;

import java.util.List;

public interface ScreeningRepository {
    List<StockScreeningResult> findStocks(ScreeningCriteria criteria);

    /** 按代码集取最新快照日全维度行（自选列表/添加校验用）；空集返回空列表。 */
    List<StockScreeningResult> findStocksByCodes(List<String> codes);

    /** 最新快照日代码前缀/名称包含搜索（自选搜索框候选）。 */
    List<StockSearchHit> searchLatestSnapshot(String keyword, int limit);
}

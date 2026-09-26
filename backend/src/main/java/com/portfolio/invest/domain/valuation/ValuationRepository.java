package com.portfolio.invest.domain.valuation;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface ValuationRepository {

    ValuationSnapshot findLatestSnapshot();

    List<ValuationSnapshot> findAllSnapshots();

    /** 最新交易日的全部证券代码（stock_valuation_daily 快照）——CSV 导入 L3 代码存在性校验数据源。 */
    Set<String> findLatestSnapshotStockCodes();

    List<IndustryValuation> findIndustryValuationsByDay(LocalDate tradingDay);

    List<TreasuryYield> findAllTreasuryYields();

    List<IndexValuation> findIndexValuations(String indexCode);

    List<ShenwanIndustryMapping> findAllIndustryMappings();
}

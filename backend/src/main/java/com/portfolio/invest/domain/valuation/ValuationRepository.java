package com.portfolio.invest.domain.valuation;

import java.time.LocalDate;
import java.util.List;

public interface ValuationRepository {

    ValuationSnapshot findLatestSnapshot();

    List<ValuationSnapshot> findAllSnapshots();

    List<IndustryValuation> findIndustryValuationsByDay(LocalDate tradingDay);

    List<TreasuryYield> findAllTreasuryYields();

    List<IndexValuation> findIndexValuations(String indexCode);

    List<ShenwanIndustryMapping> findAllIndustryMappings();
}

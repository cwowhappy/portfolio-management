package com.portfolio.invest.domain.industry;

import java.time.LocalDate;
import java.util.List;

public interface IndustryRepository {
    List<IndustryValuationRow> findLatestIndustries();
    List<IndustryValuationPoint> findValuationHistorySince(LocalDate since);
    boolean existsIndustry(String industryCode);
    List<IndustryStock> findIndustryStocks(String industryCode, String sortBy, String direction, int limit);
    List<IndustryProsperitySnapshot> findIndustryProsperity();
}

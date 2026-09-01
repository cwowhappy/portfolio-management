package com.portfolio.invest.domain.screening;

import java.util.List;

public interface ScreeningRepository {
    List<StockScreeningResult> findStocks(ScreeningCriteria criteria);
}

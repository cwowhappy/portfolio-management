package com.portfolio.invest.domain.market;

import java.util.List;

/** stock_financial 读侧端口（表 owner 是 collector，后端只读——ScreeningRepository 跨域读先例）。 */
public interface FinancialRecordRepository {
    List<FinancialRecord> findByCodeLatest(String stockCode, int limit);
}

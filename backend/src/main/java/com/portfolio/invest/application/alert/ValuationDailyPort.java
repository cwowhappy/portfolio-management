package com.portfolio.invest.application.alert;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** stock_valuation_daily（collector 写入、backend 读的跨服务契约表）读取端口。 */
public interface ValuationDailyPort {

    /** 表内最新快照交易日；空表返回 empty（调用方据此跳过巡检）。 */
    Optional<LocalDate> latestTradingDay();

    /** 指定交易日的快照，key=stock_code；只含命中代码，close/pe/pb 可空。 */
    Map<String, StockMetric> snapshots(LocalDate tradingDay, Collection<String> stockCodes);
}

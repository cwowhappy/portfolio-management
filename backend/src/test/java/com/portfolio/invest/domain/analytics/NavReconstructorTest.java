package com.portfolio.invest.domain.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import static org.assertj.core.api.Assertions.assertThat;

class NavReconstructorTest {

    private static SortedMap<LocalDate, BigDecimal> closes(Object... datePricePairs) {
        TreeMap<LocalDate, BigDecimal> m = new TreeMap<>();
        for (int i = 0; i < datePricePairs.length; i += 2) {
            m.put((LocalDate) datePricePairs[i], new BigDecimal((String) datePricePairs[i + 1]));
        }
        return m;
    }

    @DisplayName("已知答案：买入后三日净值=持仓市值+现金")
    @Test
    void givenBuyAndDeposit_whenReconstruct_thenTotalsMatchHandCalc() {
        // 2026-01-02 转入 1000；01-05 买 600519 100股@10 fee0；收盘 10/11/12
        var series = NavReconstructor.reconstruct(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7),
                List.of(new StockEvent(LocalDate.of(2026, 1, 5), "600519", new BigDecimal("100"))),
                List.of(new CashEvent(LocalDate.of(2026, 1, 2), new BigDecimal("1000")),
                        new CashEvent(LocalDate.of(2026, 1, 5), new BigDecimal("-1000"))),
                Map.of("600519", closes(LocalDate.of(2026, 1, 5), "10",
                        LocalDate.of(2026, 1, 6), "11",
                        LocalDate.of(2026, 1, 7), "12")));
        assertThat(series.points()).hasSize(3);
        assertThat(series.points().get(0).totalValue()).isEqualByComparingTo("1000");  // 100×10+0
        assertThat(series.points().get(1).totalValue()).isEqualByComparingTo("1100");
        assertThat(series.points().get(2).totalValue()).isEqualByComparingTo("1200");
        assertThat(series.points().get(2).cashBalance()).isEqualByComparingTo("0");
        assertThat(series.points().get(2).marketValue()).isEqualByComparingTo("1200");
    }

    @DisplayName("收盘缺失日 forward-fill 用最近前收盘")
    @Test
    void givenMissingClose_whenReconstruct_thenForwardFills() {
        // 01-06 无收盘 → 用 01-05 的 10
        var series = NavReconstructor.reconstruct(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 6),
                List.of(new StockEvent(LocalDate.of(2026, 1, 5), "600519", new BigDecimal("100"))),
                List.of(new CashEvent(LocalDate.of(2026, 1, 2), new BigDecimal("1000")),
                        new CashEvent(LocalDate.of(2026, 1, 5), new BigDecimal("-1000"))),
                Map.of("600519", closes(LocalDate.of(2026, 1, 5), "10")));
        assertThat(series.points()).hasSize(1);  // 日期并集只有 01-05
    }

    @DisplayName("序列日某股无收盘时市值沿用最近前收盘（跨日保持、多股票日期并集）")
    @Test
    void givenStockMissingOnSeriesDate_whenReconstruct_thenUsesPriorClose() {
        // 600519 供序列日期（01-05/06/07 收盘 10/11/12）；000001 仅 01-05 收盘 8.0，01-06 起沿用
        var series = NavReconstructor.reconstruct(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7),
                List.of(new StockEvent(LocalDate.of(2026, 1, 5), "600519", new BigDecimal("100")),
                        new StockEvent(LocalDate.of(2026, 1, 5), "000001", new BigDecimal("100"))),
                List.of(new CashEvent(LocalDate.of(2026, 1, 2), new BigDecimal("3000")),
                        new CashEvent(LocalDate.of(2026, 1, 5), new BigDecimal("-1000")),
                        new CashEvent(LocalDate.of(2026, 1, 5), new BigDecimal("-800"))),
                Map.of("600519", closes(LocalDate.of(2026, 1, 5), "10",
                        LocalDate.of(2026, 1, 6), "11",
                        LocalDate.of(2026, 1, 7), "12"),
                        "000001", closes(LocalDate.of(2026, 1, 5), "8.0")));
        assertThat(series.points()).hasSize(3);  // 日期并集 = 600519 的三个收盘日
        assertThat(series.points().get(1).marketValue()).isEqualByComparingTo("1900");  // 100×11 + 100×8.0（沿用）
        assertThat(series.points().get(2).marketValue()).isEqualByComparingTo("2000");  // 100×12 + 100×8.0（沿用）
        assertThat(series.points().get(2).totalValue()).isEqualByComparingTo("3200");   // 1200+2000，现金跨日不变
    }

    @DisplayName("卖出与现金股息进现金、送股增数量（事件按日期累积）")
    @Test
    void givenSellAndDividends_whenReconstruct_thenCashAndQtyAccumulate() {
        // 01-05 买100@10（−1000）；01-07 卖50@12 fee0（+600）；01-08 每10派2现金股息（50股→+10）；01-09 送股 10%（→55股）
        var series = NavReconstructor.reconstruct(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 9),
                List.of(new StockEvent(LocalDate.of(2026, 1, 5), "600519", new BigDecimal("100")),
                        new StockEvent(LocalDate.of(2026, 1, 7), "600519", new BigDecimal("-50")),
                        new StockEvent(LocalDate.of(2026, 1, 9), "600519", new BigDecimal("5"))),
                List.of(new CashEvent(LocalDate.of(2026, 1, 2), new BigDecimal("1000")),
                        new CashEvent(LocalDate.of(2026, 1, 5), new BigDecimal("-1000")),
                        new CashEvent(LocalDate.of(2026, 1, 7), new BigDecimal("600")),
                        new CashEvent(LocalDate.of(2026, 1, 8), new BigDecimal("10"))),
                Map.of("600519", closes(LocalDate.of(2026, 1, 5), "10", LocalDate.of(2026, 1, 6), "10",
                        LocalDate.of(2026, 1, 7), "12", LocalDate.of(2026, 1, 8), "12",
                        LocalDate.of(2026, 1, 9), "12")));
        var last = series.points().get(series.points().size() - 1);
        assertThat(last.marketValue()).isEqualByComparingTo("660");   // 55×12
        assertThat(last.cashBalance()).isEqualByComparingTo("610");   // 0+600+10
        assertThat(last.totalValue()).isEqualByComparingTo("1270");
    }

    @DisplayName("窗口早于首笔事件时现金从 0 起、无持仓日市值为 0")
    @Test
    void givenEventsAfterWindow_whenReconstruct_thenZeroUntilEvents() {
        var series = NavReconstructor.reconstruct(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7),
                List.of(), List.of(),
                Map.of("600519", closes(LocalDate.of(2026, 1, 5), "10",
                        LocalDate.of(2026, 1, 6), "11", LocalDate.of(2026, 1, 7), "12")));
        assertThat(series.points()).hasSize(3);
        assertThat(series.points().get(2).totalValue()).isEqualByComparingTo("0");
    }
}

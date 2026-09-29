package com.portfolio.invest.application.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.alert.StockMetric;
import com.portfolio.invest.application.alert.ValuationDailyPort;
import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.research.MarketSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 快照组装切片：close 行情源 / pe·pb stock_valuation_daily 源（Ruling-17 口径文案 + 缺数据 null）。 */
class MarketSnapshotAssemblerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    private final MarketDataService marketData = mock(MarketDataService.class);
    private final ValuationDailyPort valuationDaily = mock(ValuationDailyPort.class);
    private MarketSnapshotAssembler assembler;

    @BeforeEach
    void setUp() {
        // 固定时钟（Asia/Shanghai 2026-09-28 09:00）：priceNote 日期确定性
        Clock clock = Clock.fixed(Instant.parse("2026-09-28T01:00:00Z"), ZoneId.of("Asia/Shanghai"));
        assembler = new MarketSnapshotAssembler(marketData, valuationDaily, clock);
    }

    private static Quote quote(double price) {
        return new Quote("600519", "贵州茅台", price, 0, 0, 0, 0, 0, 0, 0, 0, null, null, "2026-09-28 15:00:00");
    }

    @DisplayName("双源齐备：close 取行情、pe/pb 取 stock_valuation_daily，priceNote「东财收盘及估值」（Ruling-17）")
    @Test
    void givenQuoteAndValuation_whenAssemble_thenBothSourcesMergedWithCombinedNote() {
        when(marketData.quote("600519")).thenReturn(quote(12.34));
        when(valuationDaily.latestTradingDay()).thenReturn(Optional.of(TODAY));
        when(valuationDaily.snapshots(TODAY, java.util.List.of("600519"))).thenReturn(Map.of("600519",
                new StockMetric("600519", "贵州茅台", new BigDecimal("12.34"),
                        new BigDecimal("25.5"), new BigDecimal("8.2"))));

        MarketSnapshot snapshot = assembler.assemble("600519");

        assertThat(snapshot.close()).isEqualByComparingTo("12.34");
        assertThat(snapshot.pe()).isEqualByComparingTo("25.5");
        assertThat(snapshot.pb()).isEqualByComparingTo("8.2");
        assertThat(snapshot.priceNote()).isEqualTo("东财收盘及估值 2026-09-28");
    }

    @DisplayName("估值缺失：pe/pb null，priceNote 回落「东财收盘」（Ruling-17 仅 close 口径）")
    @Test
    void givenNoValuationSnapshot_whenAssemble_thenPePbNullAndCloseOnlyNote() {
        when(marketData.quote("600519")).thenReturn(quote(12.34));
        when(valuationDaily.latestTradingDay()).thenReturn(Optional.empty());

        MarketSnapshot snapshot = assembler.assemble("600519");

        assertThat(snapshot.close()).isEqualByComparingTo("12.34");
        assertThat(snapshot.pe()).isNull();
        assertThat(snapshot.pb()).isNull();
        assertThat(snapshot.priceNote()).isEqualTo("东财收盘 2026-09-28");
    }

    @DisplayName("估值表有交易日但该票无行：pe/pb null（evaluator skipped 语义）")
    @Test
    void givenTradingDayWithoutStockRow_whenAssemble_thenPePbNull() {
        when(marketData.quote("600519")).thenReturn(quote(12.34));
        when(valuationDaily.latestTradingDay()).thenReturn(Optional.of(TODAY));
        when(valuationDaily.snapshots(TODAY, java.util.List.of("600519"))).thenReturn(Map.of());

        MarketSnapshot snapshot = assembler.assemble("600519");

        assertThat(snapshot.pe()).isNull();
        assertThat(snapshot.pb()).isNull();
        assertThat(snapshot.priceNote()).isEqualTo("东财收盘 2026-09-28");
    }

    @DisplayName("行情异常：close null 不自动命中（D10 fail-safe）；pe 仍可得 → 双口径文案")
    @Test
    void givenQuoteFailureButValuationPresent_whenAssemble_thenCloseNullPeKept() {
        when(marketData.quote("600519")).thenThrow(
                new MarketDataException("SOURCE_UNAVAILABLE", "行情源不可用"));
        when(valuationDaily.latestTradingDay()).thenReturn(Optional.of(TODAY));
        when(valuationDaily.snapshots(TODAY, java.util.List.of("600519"))).thenReturn(Map.of("600519",
                new StockMetric("600519", "贵州茅台", new BigDecimal("12.34"),
                        new BigDecimal("25.5"), null)));

        MarketSnapshot snapshot = assembler.assemble("600519");

        assertThat(snapshot.close()).isNull();
        assertThat(snapshot.pe()).isEqualByComparingTo("25.5");
        assertThat(snapshot.pb()).isNull();
        assertThat(snapshot.priceNote()).isEqualTo("东财收盘及估值 2026-09-28");
    }

    @DisplayName("行情返回 null：close null（源无该票）")
    @Test
    void givenNullQuote_whenAssemble_thenCloseNull() {
        when(marketData.quote("600519")).thenReturn(null);
        when(valuationDaily.latestTradingDay()).thenReturn(Optional.empty());

        MarketSnapshot snapshot = assembler.assemble("600519");

        assertThat(snapshot.close()).isNull();
        assertThat(snapshot.priceNote()).isEqualTo("东财收盘 2026-09-28");
    }
}

package com.portfolio.invest.agent.chart;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.market.FinancialIndicator;
import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.market.IndexQuote;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.valuation.ValuationSnapshot;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 双端契约 fixtures：后端序列化产物必须与前端 zod 解析的同一份文件逐字符一致（防字段漂移）。 */
class ChartSpecFixtureTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final Path FIXTURES = Path.of("../features/chat-rich-content/fixtures");

    @DisplayName("kline.json 逐字符等于 candlestick 序列化产物")
    @Test
    void givenTwoBarsWithMaWindow_whenKline_thenMatchesKlineFixtureCharByChar() throws Exception {
        List<KlineBar> bars = List.of(
                new KlineBar("2026-09-09", 1800.5, 1850.2, 1860.0, 1790.1, 120_000, 0, 0),
                new KlineBar("2026-09-10", 1850.2, 1840.0, 1870.3, 1830.5, 98_000, 0, 0));
        assertThat(mapper.writeValueAsString(ChartSpecs.kline("600519", "day", bars, 2)))
                .isEqualTo(Files.readString(FIXTURES.resolve("kline.json")).trim());
    }

    @DisplayName("valuation.json 逐字符等于 line 序列化产物（null 缺口保留）")
    @Test
    void givenSnapshotsWithMissingPb_whenValuationLine_thenMatchesValuationFixtureCharByChar() throws Exception {
        var snapshots = List.of(
                new ValuationSnapshot(LocalDate.parse("2026-09-09"),
                        new BigDecimal("25.1"), new BigDecimal("2.10"), 0, null),
                new ValuationSnapshot(LocalDate.parse("2026-09-10"),
                        new BigDecimal("25.3"), null, 0, null)); // PB 缺失日
        assertThat(mapper.writeValueAsString(ChartSpecs.valuationLine(snapshots)))
                .isEqualTo(Files.readString(FIXTURES.resolve("valuation.json")).trim());
    }

    @DisplayName("overview.json 逐字符等于 bar 序列化产物（仅涨跌幅）")
    @Test
    void givenMarketOverview_whenOverviewBar_thenMatchesOverviewFixtureCharByChar() throws Exception {
        var overview = new MarketOverview("2026-09-11 15:00", List.of(
                new IndexQuote("000001", "上证指数", 3200.5, 25.1, 0.79),
                new IndexQuote("399001", "深证成指", 10500.2, -32.7, -0.31)));
        assertThat(mapper.writeValueAsString(ChartSpecs.overviewBar(overview)))
                .isEqualTo(Files.readString(FIXTURES.resolve("overview.json")).trim());
    }

    @DisplayName("financials.json 逐字符等于 table 序列化产物（金额转亿）")
    @Test
    void givenFinancials_whenFinancialsTable_thenMatchesFinancialsFixtureCharByChar() throws Exception {
        var f = new Financials("600519", "贵州茅台", 22.1, 8.5, List.of(
                new FinancialIndicator(
                        "2026-06-30", 24.2, 190.1, 9.0e10, 4.2e10, 18.2, 91.5)));
        assertThat(mapper.writeValueAsString(ChartSpecs.financialsTable(f)))
                .isEqualTo(Files.readString(FIXTURES.resolve("financials.json")).trim());
    }
}

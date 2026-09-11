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
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Jackson 序列化形态钉住：与前端 lib/chart-spec.ts（zod）一一对齐。 */
class ChartSpecsTest {

    private final ObjectMapper mapper = new ObjectMapper(); // 组件名直出，无需模块

    @DisplayName("kline 序列化为 candlestick，klines 数组顺序开收低高")
    @Test
    void givenKlineBarsWithMaWindow_whenKline_thenSerializesCandlestickAsOpenCloseLowHighArrays() throws Exception {
        List<KlineBar> bars = List.of(
                new KlineBar("2026-09-09", 1800.5, 1850.2, 1860.0, 1790.1, 120_000, 0, 0),
                new KlineBar("2026-09-10", 1850.2, 1840.0, 1870.3, 1830.5, 98_000, 0, 0),
                new KlineBar("2026-09-11", 1840.0, 1880.5, 1890.0, 1835.0, 111_000, 0, 0));
        ChartSpec spec = ChartSpecs.kline("600519", "day", bars, 2); // MA2 便于断言预热
        String json = mapper.writeValueAsString(spec);
        assertThat(json).contains("\"type\":\"candlestick\"").contains("\"specVersion\":1");
        assertThat(json).contains("\"dates\":[\"2026-09-09\",\"2026-09-10\",\"2026-09-11\"]");
        // ⚠ [开,收,低,高]：1800.5=开 1850.2=收 1790.1=低 1860.0=高
        assertThat(json).contains("\"klines\":[[1800.5,1850.2,1790.1,1860.0]");
        assertThat(json).contains("\"volumes\":[120000,98000,111000]");
        // MA2（收盘价均值）：首元素 null（预热），(1850.2+1840.0)/2=1845.1、(1840.0+1880.5)/2=1860.25
        // （brief 原文 1825.35 = (open0+close0)/2，系笔误；MA 按实现代码口径取收盘价）
        assertThat(json).contains("\"mas\":[{\"name\":\"MA2\",\"data\":[null,1845.1,1860.25]}]");
    }

    @DisplayName("kline 摘要含最新与区间")
    @Test
    void givenKlineBars_whenKlineSummary_thenContainsLatestAndRange() {
        List<KlineBar> bars = List.of(
                new KlineBar("2026-09-09", 1800.0, 1810.0, 1820.0, 1790.0, 1, 0, 0),
                new KlineBar("2026-09-10", 1810.0, 1850.0, 1860.0, 1800.0, 2, 0, 0));
        assertThat(ChartSpecs.klineSummary("600519", "day", bars))
                .isEqualTo("600519 日K 2根：最新 1850.00，区间 [1790.00, 1860.00]");
    }

    @DisplayName("valuationLine PE/PB 中位数历史，null 缺口保留且 null 字段省略")
    @Test
    void givenSnapshotsWithMissingPb_whenValuationLine_thenNullGapsKeptAndNullFieldsOmitted() throws Exception {
        var snapshots = List.of(
                new ValuationSnapshot(LocalDate.parse("2026-09-09"),
                        new BigDecimal("25.1"), new BigDecimal("2.10"), 0, null),
                new ValuationSnapshot(LocalDate.parse("2026-09-10"),
                        new BigDecimal("25.3"), null, 0, null)); // PB 缺失日
        String json = mapper.writeValueAsString(ChartSpecs.valuationLine(snapshots));
        assertThat(json).contains("\"type\":\"line\"");
        assertThat(json).contains("\"categories\":[\"2026-09-09\",\"2026-09-10\"]");
        assertThat(json).contains("\"data\":[25.1,25.3]").contains("\"data\":[2.1,null]");
        assertThat(json).doesNotContain("\"subtitle\""); // NON_NULL：null 字段整个省略
    }

    @DisplayName("overviewBar 只画涨跌幅")
    @Test
    void givenMarketOverview_whenOverviewBar_thenPlotChangePctOnly() throws Exception {
        var overview = new MarketOverview("2026-09-11 15:00", List.of(
                new IndexQuote("000001", "上证指数", 3200.5, 25.1, 0.79),
                new IndexQuote("399001", "深证成指", 10500.2, -32.7, -0.31)));
        String json = mapper.writeValueAsString(ChartSpecs.overviewBar(overview));
        assertThat(json).contains("\"type\":\"bar\"").contains("\"unit\":\"%\"");
        assertThat(json).contains("\"categories\":[\"上证指数\",\"深证成指\"]");
        assertThat(json).contains("\"data\":[0.79,-0.31]");
        assertThat(json).doesNotContain("3200.5"); // 点位不进图
    }

    @DisplayName("financialsTable 列与行映射，金额转亿")
    @Test
    void givenFinancials_whenFinancialsTable_thenMapsColumnsRowsAndConvertsToYi() throws Exception {
        var f = new Financials("600519", "贵州茅台", 22.1, 8.5, List.of(
                new FinancialIndicator(
                        "2026-06-30", 24.2, 190.1, 9.0e10, 4.2e10, 18.2, 91.5)));
        String json = mapper.writeValueAsString(ChartSpecs.financialsTable(f));
        assertThat(json).contains("\"type\":\"table\"").contains("\"title\":\"600519 贵州茅台 财务指标\"");
        assertThat(json).contains("\"label\":\"报告期\"").contains("\"label\":\"每股收益EPS\"");
        assertThat(json).contains("{\"reportDate\":\"2026-06-30\",\"eps\":24.2,\"bps\":190.1,")
                .contains("\"revenueYi\":900.0").contains("\"netProfitYi\":420.0")
                .contains("\"roe\":18.2").contains("\"grossMargin\":91.5}");
    }
}

package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.invest.domain.market.FinancialIndicator;
import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.market.IndexQuote;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.NewsItem;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.market.StockHit;
import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.application.valuation.ValuationApplicationService;
import com.portfolio.invest.application.valuation.ValuationHistoryView;
import com.portfolio.invest.application.valuation.ValuationOverviewView;
import com.portfolio.invest.domain.valuation.ValuationSnapshot;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolEmitter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.portfolio.invest.domain.market.MarketDataErrorCode;

/** 7 个 Agent 工具的 JSON 输出与错误兜底。 */
class InvestToolsTest {

    private MarketDataService market;
    private ValuationApplicationService valuationService;
    private InvestTools tools;

    @BeforeEach
    void setUp() {
        market = mock(MarketDataService.class);
        valuationService = mock(ValuationApplicationService.class);
        // 复刻 Spring Boot 对 ObjectMapper 的配置（JavaTimeModule + ISO 日期，不写时间戳）
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        tools = new InvestTools(market, valuationService, mapper);
    }

    private static Quote quote(String code, Double pe, Double pb) {
        return new Quote(code, "贵州茅台", 1680.5, 5.2, 0.31, 1670, 1690, 1665, 1675.3,
                3_200_000, 5.4e9, pe, pb, "2026-08-18 15:00");
    }

    @DisplayName("searchStock序列化为JSON")
    @Test
    void whenSearchStock_thenSerializesToJson() {
        when(market.search("茅台")).thenReturn(List.of(new StockHit("600519", "贵州茅台", "1", "沪A")));
        String json = tools.searchStock("茅台");
        assertThat(json).contains("600519").contains("贵州茅台").contains("沪A");
    }

    @DisplayName("getQuote序列化为JSON")
    @Test
    void whenGetQuote_thenSerializesToJson() {
        when(market.quote("600519")).thenReturn(quote("600519", 19.95, 8.5));
        String json = tools.getQuote("600519");
        assertThat(json).contains("1680.5").contains("19.95").contains("8.5");
    }

    @DisplayName("getKline双通道：emit全量ChartSpec，返回摘要")
    @Test
    void givenKlineBars_whenGetKline_thenEmitsFullChartSpecAndReturnsSummary() throws Exception {
        when(market.kline("600519", "day", 120)).thenReturn(List.of(
                new KlineBar("2026-09-09", 1800.0, 1850.0, 1860.0, 1790.0, 120_000, 0, 0),
                new KlineBar("2026-09-10", 1850.0, 1840.0, 1870.0, 1830.0, 98_000, 0, 0)));
        ToolResultBlock[] emitted = new ToolResultBlock[1];
        ToolEmitter capture = block -> emitted[0] = block;

        var result = tools.getKline("600519", null, null, capture);

        // ① emit 全量：SSE 通道收到 ChartSpec JSON（文本块）
        String emittedText = ((TextBlock) emitted[0].getOutput().get(0)).getText();
        assertThat(emittedText)
                .contains("\"type\":\"candlestick\"")
                .contains("\"specVersion\":1");
        // ② 返回摘要：只进 LLM/stateStore，不含 specVersion。
        // state 实测 RUNNING（javap 核实 2.0.3：text() 走 4 参构造，state=null 默认 RUNNING；
        // ReActAgent.determineToolResultState 组装最终 Msg 时才把无错误块的 RUNNING 归一为 SUCCESS）
        assertThat(result.getState().toString()).isEqualTo("RUNNING");
        assertThat(result.getOutput().get(0).toString()).contains("600519 日K 2根");
        assertThat(emittedText).doesNotContain("日K 2根");
    }

    @DisplayName("getKline失败：不emit，返回错误JSON")
    @Test
    void givenSourceDown_whenGetKline_thenNoEmitAndReturnsErrorJson() {
        when(market.kline(any(), any(), anyInt()))
                .thenThrow(new MarketDataException("SOURCE_DOWN", "数据源超时"));
        ToolResultBlock[] emitted = new ToolResultBlock[1];
        ToolEmitter capture = block -> emitted[0] = block;

        var result = tools.getKline("600519", null, null, capture);

        assertThat(emitted[0]).as("失败不 emit（SSE 无 ChartSpec）").isNull();
        assertThat(result.getOutput().get(0).toString()).contains("\"error\"");
    }

    @DisplayName("getKline空bars：emit前守卫，不emit返回安全摘要")
    @Test
    void givenEmptyKlineBars_whenGetKline_thenNoEmitAndReturnsSafeSummary() {
        when(market.kline("600519", "day", 120)).thenReturn(List.of());
        ToolResultBlock[] emitted = new ToolResultBlock[1];
        ToolEmitter capture = block -> emitted[0] = block;

        var result = tools.getKline("600519", null, null, capture);

        assertThat(emitted[0]).as("空 bars 不 emit（SSE 无空 ChartSpec）").isNull();
        assertThat(result.getOutput().get(0).toString()).contains("600519 日K 暂无数据");
    }

    @DisplayName("getKline limit超上限：夹到500")
    @Test
    void givenLimitOverMax_whenGetKline_thenClampsTo500() {
        when(market.kline("600519", "day", 500)).thenReturn(List.of(
                new KlineBar("2026-09-10", 1850.0, 1840.0, 1870.0, 1830.0, 98_000, 0, 0)));
        ToolResultBlock[] emitted = new ToolResultBlock[1];
        ToolEmitter capture = block -> emitted[0] = block;

        tools.getKline("600519", "day", 999, capture);

        verify(market).kline("600519", "day", 500);
        assertThat(emitted[0]).as("clamp 不影响正常双通道").isNotNull();
    }

    @DisplayName("getFinancials双通道：emit指标table，返回PE/PB摘要")
    @Test
    void givenFinancials_whenGetFinancials_thenEmitsTableSpecAndReturnsSummary() {
        // 降序（新→旧）：与真实管线一致（EastmoneyClient sortTypes=-1；OrchestratingMarketDataService 以 get(0) 为最新）
        var newest = new FinancialIndicator("2026-06-30", 2.0, 10.0, 8.19e10, 2.3e10, 12.5, 50.2);
        var mid = new FinancialIndicator("2026-03-31", 1.0, null, null, null, null, null);       // null 金额 → 行输出 null 而非 0（T1）
        var oldest = new FinancialIndicator("2025-12-31", 0.9, 9.0, -1.23e9, -4.56e8, null, null); // 负值取整（原用例并入）
        when(market.financials("600519")).thenReturn(
                new Financials("600519", "贵州茅台", 19.95, 8.5, List.of(newest, mid, oldest)));
        ToolResultBlock[] emitted = new ToolResultBlock[1];
        ToolEmitter capture = block -> emitted[0] = block;

        var result = tools.getFinancials("600519", capture);

        // ① emit 全量：table 列/行映射；金额转亿、null 保 null、负值取整；rows 保持源序（新在前是正确展示序）
        String emittedText = ((TextBlock) emitted[0].getOutput().get(0)).getText();
        assertThat(emittedText)
                .contains("\"type\":\"table\"").contains("\"label\":\"报告期\"")
                .contains("\"revenueYi\":819.0").contains("\"netProfitYi\":230.0")
                .contains("\"revenueYi\":null")
                .contains("\"revenueYi\":-12.3").contains("\"netProfitYi\":-4.56")
                .doesNotContain("PE 19.95");
        assertThat(emittedText.indexOf("2026-06-30"))
                .as("表格行保持源序：新报告期在前").isLessThan(emittedText.indexOf("2025-12-31"));
        // ② 返回摘要：PE/PB 与最新报告期（降序首期）进 LLM/stateStore
        assertThat(result.getState().toString()).isEqualTo("RUNNING");
        assertThat(result.getOutput().get(0).toString())
                .contains("PE 19.95 / PB 8.5").contains("最新报告期 2026-06-30").contains("见表格")
                .doesNotContain("specVersion");
    }

    @DisplayName("getFinancials空指标：emit前守卫，不emit返回安全摘要")
    @Test
    void givenEmptyIndicators_whenGetFinancials_thenNoEmitAndReturnsSafeSummary() {
        when(market.financials("600519")).thenReturn(
                new Financials("600519", "贵州茅台", 19.95, 8.5, List.of()));
        ToolResultBlock[] emitted = new ToolResultBlock[1];
        ToolEmitter capture = block -> emitted[0] = block;

        var result = tools.getFinancials("600519", capture);

        assertThat(emitted[0]).as("空指标不 emit（SSE 无空表格 spec）").isNull();
        assertThat(result.getOutput().get(0).toString()).contains("暂无财务数据");
    }

    @DisplayName("getFinancials失败：不emit，返回错误JSON")
    @Test
    void givenSourceDown_whenGetFinancials_thenNoEmitAndReturnsErrorJson() {
        when(market.financials("600519")).thenThrow(new MarketDataException("SOURCE_DOWN", "数据源超时"));
        ToolResultBlock[] emitted = new ToolResultBlock[1];
        ToolEmitter capture = block -> emitted[0] = block;

        var result = tools.getFinancials("600519", capture);

        assertThat(emitted[0]).as("失败不 emit（SSE 无 ChartSpec）").isNull();
        assertThat(result.getOutput().get(0).toString()).contains("\"error\"");
    }

    @DisplayName("getNews参数缺省回退10")
    @Test
    void givenParamsMissing_whenGetNews_thenDefaultsTo10() {
        var item = new NewsItem("标题", "摘要", "来源", "2026-08-18", "https://x/1");
        when(market.news("600519", 10)).thenReturn(List.of(item));
        String json = tools.getNews("600519", null);
        assertThat(json).contains("标题").contains("摘要");
        when(market.news("600519", 5)).thenReturn(List.of());
        assertThat(tools.getNews("600519", 5)).isEqualTo("[]");
    }

    @DisplayName("getMarketOverview双通道：emit涨跌幅bar，返回含点位摘要")
    @Test
    void givenOverviewIndices_whenGetMarketOverview_thenEmitsBarSpecAndReturnsSummary() {
        var sh = new IndexQuote("sh000001", "上证指数", 3000.1, 10.2, 0.34);
        var sz = new IndexQuote("399001", "深证成指", 10500.2, -32.7, -0.31);
        when(market.overview()).thenReturn(new MarketOverview("2026-09-11 15:00", List.of(sh, sz)));
        ToolResultBlock[] emitted = new ToolResultBlock[1];
        ToolEmitter capture = block -> emitted[0] = block;

        var result = tools.getMarketOverview(capture);

        // ① emit 全量：bar 只画涨跌幅，点位不进图
        String emittedText = ((TextBlock) emitted[0].getOutput().get(0)).getText();
        assertThat(emittedText)
                .contains("\"type\":\"bar\"").contains("\"unit\":\"%\"")
                .doesNotContain("3000.1");
        // ② 返回摘要：指数名与点位进 LLM/stateStore
        assertThat(result.getState().toString()).isEqualTo("RUNNING");
        assertThat(result.getOutput().get(0).toString())
                .contains("上证指数").contains("3000.10")
                .doesNotContain("specVersion");
    }

    @DisplayName("getMarketOverview失败：不emit，返回错误JSON")
    @Test
    void givenSourceDown_whenGetMarketOverview_thenNoEmitAndReturnsErrorJson() {
        when(market.overview()).thenThrow(new MarketDataException("SOURCE_DOWN", "数据源超时"));
        ToolResultBlock[] emitted = new ToolResultBlock[1];
        ToolEmitter capture = block -> emitted[0] = block;

        var result = tools.getMarketOverview(capture);

        assertThat(emitted[0]).as("失败不 emit（SSE 无 ChartSpec）").isNull();
        assertThat(result.getOutput().get(0).toString()).contains("\"error\"");
    }

    @DisplayName("业务异常返回结构化错误不抛出")
    @Test
    void givenBusinessException_whenSearchStock_thenReturnsStructuredError() {
        when(market.search("茅台")).thenThrow(new MarketDataException(MarketDataErrorCode.INVALID_QUERY, "搜索关键词不能为空"));
        String json = tools.searchStock("茅台");
        assertThat(json).contains("\"error\":\"搜索关键词不能为空\"").contains("\"hint\":\"数据源暂不可用，请稍后重试或换个问法\"");
    }

    @DisplayName("未知异常兜底为工具执行失败")
    @Test
    void givenUnknownException_whenGetQuote_thenFallsBackToToolFailure() {
        when(market.quote("600519")).thenThrow(new IllegalStateException("boom"));
        String json = tools.getQuote("600519");
        assertThat(json).contains("\"error\":\"工具执行失败\"").contains("\"hint\":\"请稍后重试\"");
    }

    @DisplayName("getValuation双通道：emit估值走势line，返回含分位温度计摘要")
    @Test
    void givenHistoryAndOverview_whenGetValuation_thenEmitsLineSpecAndReturnsSummary() {
        // 图数据来自 history()（overview() 视图无 peHistory/pbHistory 字段）；PB 缺失日留 null 缺口
        when(valuationService.history()).thenReturn(new ValuationHistoryView(List.of(
                new ValuationSnapshot(LocalDate.of(2026, 9, 10),
                        new BigDecimal("25.1"), new BigDecimal("2.1"), 0, null),
                new ValuationSnapshot(LocalDate.of(2026, 9, 11),
                        new BigDecimal("25.3"), null, 0, null)),
                List.of(), List.of()));
        var snapshot = new ValuationOverviewView.SnapshotView(
                LocalDate.of(2026, 9, 11), new BigDecimal("19.14"), new BigDecimal("1.68"), 220,
                new BigDecimal("0.041"));
        // 指数估值进摘要（设计规格 §二.3：ERP/指数估值/温度计入摘要）
        when(valuationService.overview()).thenReturn(new ValuationOverviewView(
                snapshot, new BigDecimal("55"), new BigDecimal("60"), new BigDecimal("40"),
                new BigDecimal("0.5"), new BigDecimal("10"), new BigDecimal("80"),
                List.of(new ValuationOverviewView.IndexValuationView("000300", "沪深300",
                        new BigDecimal("12.5"), new BigDecimal("1.4"), new BigDecimal("2.5"),
                        new BigDecimal("65"), new BigDecimal("70"))),
                true));
        ToolResultBlock[] emitted = new ToolResultBlock[1];
        ToolEmitter capture = block -> emitted[0] = block;

        var result = tools.getValuation(capture);

        // ① emit 全量：PE/PB 中位数历史 line（PB 序列含 null 缺口）
        String emittedText = ((TextBlock) emitted[0].getOutput().get(0)).getText();
        assertThat(emittedText)
                .contains("\"type\":\"line\"")
                .contains("\"data\":[2.1,null]")
                .doesNotContain("温度计");
        // ② 返回摘要：分位/ERP/温度计/指数估值进 LLM/stateStore
        assertThat(result.getState().toString()).isEqualTo("RUNNING");
        assertThat(result.getOutput().get(0).toString())
                .contains("分位").contains("温度计")
                .contains("沪深300 PE 12.5（65 分位）")
                .doesNotContain("specVersion");
    }

    @DisplayName("getValuation冷库期：snapshots空不emit，返回积累中文本")
    @Test
    void givenEmptySnapshots_whenGetValuation_thenNoEmitAndReturnsAccumulatingSummary() {
        when(valuationService.history()).thenReturn(new ValuationHistoryView(List.of(), List.of(), List.of()));
        ToolResultBlock[] emitted = new ToolResultBlock[1];
        ToolEmitter capture = block -> emitted[0] = block;

        var result = tools.getValuation(capture);

        assertThat(emitted[0]).as("冷库期不 emit（SSE 无空走势 spec）").isNull();
        assertThat(result.getOutput().get(0).toString()).contains("估值数据积累中");
    }

    @DisplayName("getValuation失败：不emit，返回错误JSON")
    @Test
    void givenSourceDown_whenGetValuation_thenNoEmitAndReturnsErrorJson() {
        when(valuationService.history()).thenThrow(new MarketDataException("SOURCE_DOWN", "数据源超时"));
        ToolResultBlock[] emitted = new ToolResultBlock[1];
        ToolEmitter capture = block -> emitted[0] = block;

        var result = tools.getValuation(capture);

        assertThat(emitted[0]).as("失败不 emit（SSE 无 ChartSpec）").isNull();
        assertThat(result.getOutput().get(0).toString()).contains("\"error\"");
    }
}

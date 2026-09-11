package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.invest.domain.market.FinancialIndicator;
import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.NewsItem;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.market.StockHit;
import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.application.valuation.ValuationApplicationService;
import com.portfolio.invest.application.valuation.ValuationOverviewView;
import com.portfolio.invest.domain.valuation.ValuationSnapshot;
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
        io.agentscope.core.message.ToolResultBlock[] emitted = new io.agentscope.core.message.ToolResultBlock[1];
        io.agentscope.core.tool.ToolEmitter capture = block -> emitted[0] = block;

        var result = tools.getKline("600519", null, null, capture);

        // ① emit 全量：SSE 通道收到 ChartSpec JSON（文本块）
        String emittedText = ((io.agentscope.core.message.TextBlock) emitted[0].getOutput().get(0)).getText();
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
        io.agentscope.core.message.ToolResultBlock[] emitted = new io.agentscope.core.message.ToolResultBlock[1];
        io.agentscope.core.tool.ToolEmitter capture = block -> emitted[0] = block;

        var result = tools.getKline("600519", null, null, capture);

        assertThat(emitted[0]).as("失败不 emit（SSE 无 ChartSpec）").isNull();
        assertThat(result.getOutput().get(0).toString()).contains("\"error\"");
    }

    @DisplayName("getFinancials组装指标与估值")
    @Test
    void whenGetFinancials_thenBuildsIndicatorsAndValuation() {
        var i1 = new FinancialIndicator("2026-06-30", 2.0, 10.0, 8.19e10, 2.3e10, 12.5, 50.2);
        var i2 = new FinancialIndicator("2026-03-31", 1.0, null, null, null, null, null);
        when(market.financials("600519")).thenReturn(new Financials("600519", "贵州茅台", 19.95, 8.5, List.of(i1, i2)));
        String json = tools.getFinancials("600519");
        assertThat(json)
                .contains("报告期").contains("2026-06-30")
                .contains("营收(亿元)").contains("819.0")
                .contains("净利润(亿元)").contains("230.0")
                .contains("加权ROE(%)").contains("12.5")
                .contains("毛利率(%)").contains("50.2")
                .contains("19.95").contains("8.5");
        // null 指标序列化为 null
        assertThat(json).contains("2026-03-31");
    }

    @DisplayName("getFinancials的null值输出null")
    @Test
    void givenNullFinancialValues_whenGetFinancials_thenOutputsNull() {
        var i = new FinancialIndicator("2026-06-30", null, null, null, null, null, null);
        when(market.financials("600519")).thenReturn(new Financials("600519", "贵州茅台", null, null, List.of(i)));
        String json = tools.getFinancials("600519");
        assertThat(json).contains("\"每股收益EPS\":null").contains("\"营收(亿元)\":null");
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

    @DisplayName("getMarketOverview序列化指数")
    @Test
    void givenOverviewIndex_whenGetMarketOverview_thenSerializesJson() {
        var idx = new com.portfolio.invest.domain.market.IndexQuote("sh000001", "上证指数", 3000.1, 10.2, 0.34);
        when(market.overview()).thenReturn(new MarketOverview("2026-08-18 15:00", List.of(idx)));
        String json = tools.getMarketOverview();
        assertThat(json).contains("上证指数").contains("3000.1");
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

    @DisplayName("负数营收取整输出")
    @Test
    void givenNegativeRevenue_whenGetFinancials_thenOutputsRoundedValue() {
        var i = new FinancialIndicator("2026-06-30", 1.0, 10.0, -1.23e9, -4.56e8, null, null);
        when(market.financials("600519")).thenReturn(new Financials("600519", "贵州茅台", null, null, List.of(i)));
        String json = tools.getFinancials("600519");
        assertThat(json).contains("\"营收(亿元)\":-12.3").contains("\"净利润(亿元)\":-4.56");
    }

    @DisplayName("getValuation返回包含温度计的JSON")
    @Test
    void givenValuationOverview_whenGetValuation_thenReturnsJsonWithThermometer() {
        when(valuationService.overview()).thenReturn(new ValuationOverviewView(
                null, null, null, null, null, null, new BigDecimal("80"), List.of(), true));
        String json = tools.getValuation();
        assertThat(json).contains("\"thermometer\":80");
    }

    @DisplayName("getValuation序列化快照的LocalDate与完整字段")
    @Test
    void givenSnapshotView_whenGetValuation_thenSerializesLocalDateAndFullFields() {
        var snapshot = new ValuationOverviewView.SnapshotView(
                LocalDate.of(2026, 8, 27), new BigDecimal("19.14"), new BigDecimal("1.68"), 220,
                new BigDecimal("0.041"));
        var idx = new ValuationOverviewView.IndexValuationView(
                "sh000001", "上证指数", new BigDecimal("14.5"), new BigDecimal("1.4"),
                new BigDecimal("2.1"), new BigDecimal("55.0"), new BigDecimal("60.0"));
        when(valuationService.overview()).thenReturn(new ValuationOverviewView(
                snapshot,
                new BigDecimal("20.0"), new BigDecimal("30.0"), new BigDecimal("40.0"),
                new BigDecimal("5.5"), new BigDecimal("10.0"), new BigDecimal("80"),
                List.of(idx), true));
        String json = tools.getValuation();
        // 回归：LocalDate 必须按 ISO 序列化（裸 ObjectMapper 无 JavaTimeModule 会抛异常 → 「工具执行失败」）
        assertThat(json).contains("\"tradingDay\":\"2026-08-27\"");
        // 完整字段序列化：快照数值、指数列表、布尔标志
        assertThat(json)
                .contains("19.14").contains("1.68")
                .contains("220").contains("0.041")
                .contains("上证指数")
                .contains("\"dataAccumulating\":true");
    }
}

package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.invest.market.MarketDataException;
import com.portfolio.invest.market.MarketDataService;
import com.portfolio.invest.market.dto.FinancialIndicator;
import com.portfolio.invest.market.dto.Financials;
import com.portfolio.invest.market.dto.KlineBar;
import com.portfolio.invest.market.dto.MarketOverview;
import com.portfolio.invest.market.dto.NewsItem;
import com.portfolio.invest.market.dto.Quote;
import com.portfolio.invest.market.dto.StockHit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 6 个 Agent 工具的 JSON 输出与错误兜底。 */
class InvestToolsTest {

    private MarketDataService market;
    private InvestTools tools;

    @BeforeEach
    void setUp() {
        market = mock(MarketDataService.class);
        tools = new InvestTools(market);
    }

    private static Quote quote(String code, Double pe, Double pb) {
        return new Quote(code, "贵州茅台", 1680.5, 5.2, 0.31, 1670, 1690, 1665, 1675.3,
                3_200_000, 5.4e9, pe, pb, "2026-08-18 15:00");
    }

    @Test
    void searchStock序列化为JSON() {
        when(market.search("茅台")).thenReturn(List.of(new StockHit("600519", "贵州茅台", "1", "沪A")));
        String json = tools.searchStock("茅台");
        assertThat(json).contains("600519").contains("贵州茅台").contains("沪A");
    }

    @Test
    void getQuote序列化为JSON() {
        when(market.quote("600519")).thenReturn(quote("600519", 19.95, 8.5));
        String json = tools.getQuote("600519");
        assertThat(json).contains("1680.5").contains("19.95").contains("8.5");
    }

    @Test
    void getKline参数缺省时回退day与60() {
        when(market.kline("600519", "day", 60)).thenReturn(List.of());
        tools.getKline("600519", null, null);
        when(market.kline("600519", "week", 120)).thenReturn(List.of());
        String json = tools.getKline("600519", "week", 120);
        assertThat(json).isEqualTo("[]");
    }

    @Test
    void getKline序列化K线() {
        var bar = new KlineBar("2026-08-18", 10, 11, 12, 9, 1000, 10000, 1.5);
        when(market.kline("600519", "day", 60)).thenReturn(List.of(bar));
        String json = tools.getKline("600519", "day", 60);
        assertThat(json).contains("2026-08-18").contains("11.0");
    }

    @Test
    void getFinancials组装指标与估值() {
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

    @Test
    void getFinancials的null值输出null() {
        var i = new FinancialIndicator("2026-06-30", null, null, null, null, null, null);
        when(market.financials("600519")).thenReturn(new Financials("600519", "贵州茅台", null, null, List.of(i)));
        String json = tools.getFinancials("600519");
        assertThat(json).contains("\"每股收益EPS\":null").contains("\"营收(亿元)\":null");
    }

    @Test
    void getNews参数缺省回退10() {
        var item = new NewsItem("标题", "摘要", "来源", "2026-08-18", "https://x/1");
        when(market.news("600519", 10)).thenReturn(List.of(item));
        String json = tools.getNews("600519", null);
        assertThat(json).contains("标题").contains("摘要");
        when(market.news("600519", 5)).thenReturn(List.of());
        assertThat(tools.getNews("600519", 5)).isEqualTo("[]");
    }

    @Test
    void getMarketOverview序列化指数() {
        var idx = new com.portfolio.invest.market.dto.IndexQuote("sh000001", "上证指数", 3000.1, 10.2, 0.34);
        when(market.overview()).thenReturn(new MarketOverview("2026-08-18 15:00", List.of(idx)));
        String json = tools.getMarketOverview();
        assertThat(json).contains("上证指数").contains("3000.1");
    }

    @Test
    void 业务异常返回结构化错误不抛出() {
        when(market.search("茅台")).thenThrow(new MarketDataException("INVALID_QUERY", "搜索关键词不能为空"));
        String json = tools.searchStock("茅台");
        assertThat(json).contains("\"error\":\"搜索关键词不能为空\"").contains("\"hint\":\"数据源暂不可用，请稍后重试或换个问法\"");
    }

    @Test
    void 未知异常兜底为工具执行失败() {
        when(market.quote("600519")).thenThrow(new IllegalStateException("boom"));
        String json = tools.getQuote("600519");
        assertThat(json).contains("\"error\":\"工具执行失败\"").contains("\"hint\":\"请稍后重试\"");
    }

    @Test
    void 负数营收取整输出() {
        var i = new FinancialIndicator("2026-06-30", 1.0, 10.0, -1.23e9, -4.56e8, null, null);
        when(market.financials("600519")).thenReturn(new Financials("600519", "贵州茅台", null, null, List.of(i)));
        String json = tools.getFinancials("600519");
        assertThat(json).contains("\"营收(亿元)\":-12.3").contains("\"净利润(亿元)\":-4.56");
    }
}

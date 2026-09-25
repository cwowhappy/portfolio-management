package com.portfolio.invest.infrastructure.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketHttpDataSourceTest {
    private final EastmoneyClient eastmoney = mock(EastmoneyClient.class);
    private final SinaClient sina = mock(SinaClient.class);
    private final TencentClient tencent = mock(TencentClient.class);
    private final MarketHttpDataSource source = new MarketHttpDataSource(eastmoney, sina, tencent);
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode node = mapper.createObjectNode();

    @DisplayName("主源方法委托东财")
    @Test
    void whenPrimarySourceMethodCalled_thenDelegatesToEastmoney() {
        when(eastmoney.search("茅台")).thenReturn(node);
        assertThat(source.search("茅台")).isSameAs(node);
        when(eastmoney.quote("1.600519")).thenReturn(node);
        assertThat(source.quote("1.600519")).isSameAs(node);
        when(eastmoney.kline("1.600519", 101, 60)).thenReturn(node);
        assertThat(source.kline("1.600519", 101, 60)).isSameAs(node);
        when(eastmoney.financials("600519.SH")).thenReturn(node);
        assertThat(source.financials("600519.SH")).isSameAs(node);
        when(eastmoney.news("茅台", 10)).thenReturn(node);
        assertThat(source.news("茅台", 10)).isSameAs(node);
        when(eastmoney.overview()).thenReturn(node);
        assertThat(source.overview()).isSameAs(node);
    }

    @DisplayName("新浪兜底与腾讯K线委托对应客户端")
    @Test
    void whenSinaFallbackAndTencentKlineCalled_thenDelegatesToCorrespondingClients() {
        when(sina.rawQuote("sh", "600519")).thenReturn("txt");
        assertThat(source.sinaQuote("sh", "600519")).isEqualTo("txt");
        when(sina.rawIndices()).thenReturn("idx");
        assertThat(source.sinaIndices()).isEqualTo("idx");
        when(tencent.kline("sh600519", "day", 120)).thenReturn(node);
        assertThat(source.tencentKline("sh600519", "day", 120)).isSameAs(node);
        verify(tencent).kline("sh600519", "day", 120);
    }

    @DisplayName("腾讯方法委托腾讯客户端")
    @Test
    void whenTencentMethodCalled_thenDelegatesToTencent() {
        when(tencent.quote("sh600519")).thenReturn("txt");
        assertThat(source.tencentQuote("sh600519")).isEqualTo("txt");
        when(tencent.indices()).thenReturn("idx");
        assertThat(source.tencentIndices()).isEqualTo("idx");
    }
}

package com.portfolio.invest.infrastructure.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.application.market.OrchestratingMarketDataService;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.market.IndexQuote;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketDataSource;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.NewsItem;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.market.StockHit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 缓存装饰器：命中缓存不触发委托；key 按规整后参数；探活绕过缓存。 */
class CachedMarketDataServiceTest {

    private final MarketDataService delegate = mock(MarketDataService.class);
    private CachedMarketDataService service;

    @BeforeEach
    void setUp() {
        InvestProperties props = new InvestProperties();
        service = new CachedMarketDataService(new StubOrchestrator(delegate), props);
    }

    /** 构造器需要 OrchestratingMarketDataService 具体类型，用轻量子类转交 mock 接口。 */
    static final class StubOrchestrator extends OrchestratingMarketDataService {
        private final MarketDataService target;
        StubOrchestrator(MarketDataService target) {
            super(mock(MarketDataSource.class));
            this.target = target;
        }
        @Override public List<StockHit> search(String q) { return target.search(q); }
        @Override public Quote quote(String c) { return target.quote(c); }
        @Override public List<KlineBar> kline(String c, String p, int l) { return target.kline(c, p, l); }
        @Override public Financials financials(String c) { return target.financials(c); }
        @Override public List<NewsItem> news(String c, int l) { return target.news(c, l); }
        @Override public MarketOverview overview() { return target.overview(); }
        @Override public long probeQuoteLatencyMs() { return target.probeQuoteLatencyMs(); }
    }

    @DisplayName("search按trim后key缓存")
    @Test
    void searchCachesByTrimmedKey() {
        StockHit h = new StockHit("600519", "贵州茅台", "SH", "沪A");
        when(delegate.search("茅台")).thenReturn(List.of(h));
        assertThat(service.search(" 茅台 ")).hasSize(1);
        assertThat(service.search("茅台")).hasSize(1); // 同 key → 缓存命中
        verify(delegate, times(1)).search("茅台");
    }

    @DisplayName("kline按归一化参数缓存")
    @Test
    void klineCachesByNormalizedParameters() {
        when(delegate.kline("600519", "day", 60)).thenReturn(List.of());
        service.kline("600519", "day", 60);
        service.kline("600519", "day", 60);
        verify(delegate, times(1)).kline("600519", "day", 60);
    }

    @DisplayName("news按夹取条数缓存")
    @Test
    void newsCachesByClampedCount() {
        when(delegate.news("600519", 10)).thenReturn(List.of());
        service.news("600519", 10);
        service.news("600519", 0);   // 夹取到 10 → 同 key
        verify(delegate, times(1)).news("600519", 10);
        when(delegate.news("600519", 25)).thenReturn(List.of());
        service.news("600519", 25);  // 夹取到 20 → 新 key
        verify(delegate, times(1)).news("600519", 25);
    }

    @DisplayName("financials按代码缓存")
    @Test
    void financialsCachesByCode() {
        Financials f = new Financials("600519", "贵州茅台", 21.35, 7.82, List.of());
        when(delegate.financials("600519")).thenReturn(f);
        assertThat(service.financials("600519")).isSameAs(f);
        assertThat(service.financials("600519")).isSameAs(f);
        verify(delegate, times(1)).financials("600519");
    }

    @DisplayName("overview缓存")
    @Test
    void overviewIsCached() {
        MarketOverview o = new MarketOverview("2026-08-18 15:00",
                List.of(new IndexQuote("sh000001", "上证指数", 3000.1, 10.2, 0.34)));
        when(delegate.overview()).thenReturn(o);
        assertThat(service.overview()).isSameAs(o);
        assertThat(service.overview()).isSameAs(o);
        verify(delegate, times(1)).overview();
    }

    @DisplayName("probe绕过缓存")
    @Test
    void probeBypassesCache() {
        service.quote("600519");
        service.probeQuoteLatencyMs();
        verify(delegate).probeQuoteLatencyMs();
    }
}

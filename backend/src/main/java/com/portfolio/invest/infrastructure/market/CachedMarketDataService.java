package com.portfolio.invest.infrastructure.market;

import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.application.market.OrchestratingMarketDataService;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.MarketParams;
import com.portfolio.invest.domain.market.NewsItem;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.market.StockHit;
import com.portfolio.invest.domain.market.StockRef;
import com.portfolio.invest.infrastructure.cache.TtlCache;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** 缓存装饰器：包裹编排服务，仅加缓存（限流在客户端内）。探活绕过缓存直连编排。 */
@Component
@Primary
public class CachedMarketDataService implements MarketDataService {

    private final OrchestratingMarketDataService delegate;
    private final InvestProperties props;
    private final TtlCache cache;

    public CachedMarketDataService(OrchestratingMarketDataService delegate, InvestProperties props) {
        this.delegate = delegate;
        this.props = props;
        this.cache = new TtlCache(props.getMarket().getCache().getMaxEntries());
    }

    @Override
    public List<StockHit> search(String query) {
        String q = MarketParams.normalizeQuery(query);
        return cached("s:" + q, () -> delegate.search(q), props.getMarket().getCache().getSearchTtl());
    }

    @Override
    public Quote quote(String code) {
        StockRef ref = StockRef.from(code);
        return cached("q:" + ref.code(), () -> delegate.quote(code), props.getMarket().getCache().getQuoteTtl());
    }

    @Override
    public Map<String, Quote> quoteBatch(List<String> codes) {
        Map<String, Quote> result = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String code : codes) {
            String key = "q:" + StockRef.from(code).code();
            Quote hit = cache.get(key);
            if (hit != null) {
                result.put(code, hit);
            } else {
                missing.add(code);
            }
        }
        if (!missing.isEmpty()) {
            Map<String, Quote> fetched = delegate.quoteBatch(missing);
            for (Map.Entry<String, Quote> e : fetched.entrySet()) {
                cache.put("q:" + StockRef.from(e.getKey()).code(), e.getValue(),
                        props.getMarket().getCache().getQuoteTtl());
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    @Override
    public List<KlineBar> kline(String code, String period, int limit) {
        StockRef ref = StockRef.from(code);
        int klt = MarketParams.kltOf(period);
        int n = MarketParams.clampLimit(limit, MarketParams.KLINE_MIN_LIMIT, MarketParams.KLINE_DEFAULT_LIMIT, MarketParams.MAX_LIMIT);
        String key = "k:" + ref.code() + ":" + klt + ":" + n;
        return cached(key, () -> delegate.kline(code, period, limit), props.getMarket().getCache().getKlineTtl());
    }

    @Override
    public Financials financials(String code) {
        StockRef ref = StockRef.from(code);
        return cached("f:" + ref.code(), () -> delegate.financials(code), props.getMarket().getCache().getFinancialsTtl());
    }

    @Override
    public List<NewsItem> news(String code, int limit) {
        StockRef ref = StockRef.from(code);
        int n = MarketParams.clampLimit(limit, 1, MarketParams.NEWS_DEFAULT_LIMIT, MarketParams.NEWS_MAX_LIMIT);
        return cached("n:" + ref.code() + ":" + n, () -> delegate.news(code, limit), props.getMarket().getCache().getNewsTtl());
    }

    @Override
    public MarketOverview overview() {
        return cached("o:indices", delegate::overview, props.getMarket().getCache().getOverviewTtl());
    }

    @Override
    public long probeQuoteLatencyMs() {
        return delegate.probeQuoteLatencyMs();
    }

    private <T> T cached(String key, Supplier<T> loader, Duration ttl) {
        T v = cache.get(key);
        if (v != null) return v;
        v = loader.get();
        cache.put(key, v, ttl);
        return v;
    }
}

package com.portfolio.invest.application.market;

import com.portfolio.invest.domain.market.FinancialIndicator;
import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.MarketDataSource;
import com.portfolio.invest.domain.market.MarketDataParser;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.MarketParams;
import com.portfolio.invest.domain.market.NewsItem;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.market.StockHit;
import com.portfolio.invest.domain.market.StockRef;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 行情编排：主源（东财）失败降级新浪/腾讯；无缓存（缓存见 CachedMarketDataService）。 */
@Service
public class OrchestratingMarketDataService implements MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratingMarketDataService.class);

    private final MarketDataSource source;

    public OrchestratingMarketDataService(MarketDataSource source) {
        this.source = source;
    }

    @Override
    public List<StockHit> search(String query) {
        String q = MarketParams.normalizeQuery(query);
        return MarketDataParser.parseSearch(source.search(q));
    }

    @Override
    public Quote quote(String code) {
        StockRef ref = StockRef.from(code);
        return fetchQuoteFresh(ref);
    }

    private Quote fetchQuoteFresh(StockRef ref) {
        return withFallback(
                () -> MarketDataParser.parseQuote(source.quote(ref.secid())),
                () -> MarketDataParser.parseSinaQuote(source.rawQuote(ref.sinaPrefix(), ref.code()), ref.code()),
                e -> log.info("东财行情失败({}), 降级新浪: {}", ref.code(), e.getMessage()));
    }

    @Override
    public List<KlineBar> kline(String code, String period, int limit) {
        StockRef ref = StockRef.from(code);
        String periodNorm = period == null ? "day" : period;
        int klt = MarketParams.kltOf(periodNorm);
        int n = MarketParams.clampLimit(limit, MarketParams.KLINE_MIN_LIMIT, MarketParams.KLINE_DEFAULT_LIMIT, MarketParams.MAX_LIMIT);
        String symbol = ref.sinaPrefix() + ref.code();
        return withFallback(
                () -> MarketDataParser.parseKline(source.kline(ref.secid(), klt, n)),
                () -> MarketDataParser.parseTencentKline(source.fallbackKline(symbol, periodNorm, n), symbol, periodNorm),
                e -> log.info("东财K线失败({}), 降级腾讯: {}", ref.code(), e.getMessage()));
    }

    @Override
    public Financials financials(String code) {
        StockRef ref = StockRef.from(code);
        Quote q = quote(ref.code());
        var indicators = MarketDataParser.parseFinancialIndicators(source.financials(ref.secuCode()));
        Double pe = q.pe();
        Double pb = q.pb();
        if ((pe == null || pb == null) && !indicators.isEmpty()) {
            Valuation v = estimateValuation(q.price(), indicators);
            if (pe == null) pe = v.pe();
            if (pb == null) pb = v.pb();
        }
        return new Financials(ref.code(), q.name(), pe, pb, indicators);
    }

    private Valuation estimateValuation(double price, List<FinancialIndicator> indicators) {
        FinancialIndicator latest = indicators.get(0);
        Double pb = latest.bps() != null && latest.bps() > 0 ? round2(price / latest.bps()) : null;
        Double pe = null;
        FinancialIndicator annual = indicators.stream()
                .filter(i -> i.reportDate() != null && i.reportDate().endsWith("-12-31"))
                .findFirst().orElse(null);
        if (annual != null && latest.eps() != null) {
            String rd = latest.reportDate();
            if (rd != null && rd.endsWith("-12-31")) {
                pe = round2(price / latest.eps());
            } else {
                String sameLastYear = sameLastYearOf(rd);
                if (sameLastYear != null) {
                    Double sameEps = indicators.stream()
                            .filter(i -> sameLastYear.equals(i.reportDate()))
                            .map(FinancialIndicator::eps).filter(Objects::nonNull).findFirst().orElse(null);
                    if (annual.eps() != null && sameEps != null) {
                        double epsTtm = latest.eps() + annual.eps() - sameEps;
                        if (epsTtm > 0) pe = round2(price / epsTtm);
                    }
                }
            }
        }
        return new Valuation(pe, pb);
    }

    private static String sameLastYearOf(String reportDate) {
        if (reportDate == null || reportDate.length() < 4) return null;
        String y = reportDate.substring(0, 4);
        if (!y.matches("\\d{4}")) return null;
        return (Integer.parseInt(y) - 1) + reportDate.substring(4);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private record Valuation(Double pe, Double pb) {}

    @Override
    public List<NewsItem> news(String code, int limit) {
        StockRef ref = StockRef.from(code);
        int n = MarketParams.clampLimit(limit, 1, MarketParams.NEWS_DEFAULT_LIMIT, MarketParams.NEWS_MAX_LIMIT);
        String keyword = ref.code();
        try {
            String name = quote(ref.code()).name();
            if (name != null && !name.isBlank()) keyword = name;
        } catch (MarketDataException e) {
            log.warn("获取股票名称失败，新闻改用代码搜索: {}", ref.code());
        }
        return MarketDataParser.parseNews(source.news(keyword, n));
    }

    @Override
    public MarketOverview overview() {
        return withFallback(
                () -> MarketDataParser.buildOverview(source.overview()),
                () -> MarketDataParser.buildSinaOverview(source.rawIndices()),
                e -> log.info("东财指数失败({}), 降级新浪", e.getMessage()));
    }

    @Override
    public long probeQuoteLatencyMs() {
        StockRef ref = StockRef.from("600519");
        long start = System.currentTimeMillis();
        fetchQuoteFresh(ref);
        return System.currentTimeMillis() - start;
    }

    private <T> T withFallback(Supplier<T> primary, Supplier<T> fallback, Consumer<MarketDataException> onPrimaryFailure) {
        try {
            return primary.get();
        } catch (MarketDataException e) {
            onPrimaryFailure.accept(e);
            return fallback.get();
        }
    }
}

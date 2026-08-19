package com.portfolio.invest.market;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.market.dto.FinancialIndicator;
import com.portfolio.invest.market.dto.Financials;
import com.portfolio.invest.market.dto.KlineBar;
import com.portfolio.invest.market.dto.MarketOverview;
import com.portfolio.invest.market.dto.NewsItem;
import com.portfolio.invest.market.dto.Quote;
import com.portfolio.invest.market.dto.StockHit;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 行情数据服务：缓存 + 东财主源/新浪（腾讯）兜底编排。限流在客户端每次真实 HTTP 请求前执行。 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    private static final int MAX_LIMIT = 500;
    private static final int KLINE_MIN_LIMIT = 5;
    private static final int KLINE_DEFAULT_LIMIT = 120;
    private static final int NEWS_DEFAULT_LIMIT = 10;
    private static final int NEWS_MAX_LIMIT = 20;

    private final EastmoneyClient eastmoney;
    private final SinaClient sina;
    private final TencentClient tencent;
    private final InvestProperties props;
    private final TtlCache cache;

    public MarketDataService(
            EastmoneyClient eastmoney, SinaClient sina, TencentClient tencent, InvestProperties props) {
        this.eastmoney = eastmoney;
        this.sina = sina;
        this.tencent = tencent;
        this.props = props;
        this.cache = new TtlCache(props.getMarket().getCache().getMaxEntries());
    }

    public List<StockHit> search(String query) {
        if (query == null || query.isBlank()) {
            throw new MarketDataException("INVALID_QUERY", "搜索关键词不能为空");
        }
        String key = "s:" + query.trim();
        List<StockHit> hits = cache.get(key);
        if (hits != null) {
            return hits;
        }
        hits = MarketDataParser.parseSearch(eastmoney.search(query.trim()));
        cache.put(key, hits, props.getMarket().getCache().getSearchTtl());
        return hits;
    }

    public Quote quote(String code) {
        StockRef ref = StockRef.from(code);
        String key = "q:" + ref.code();
        Quote q = cache.get(key);
        if (q != null) {
            return q;
        }
        q = fetchQuoteFresh(ref);
        cache.put(key, q, props.getMarket().getCache().getQuoteTtl());
        return q;
    }

    private Quote fetchQuoteFresh(StockRef ref) {
        return withFallback(
                () -> MarketDataParser.parseQuote(eastmoney.quote(ref.secid())),
                () -> MarketDataParser.parseSinaQuote(sina.rawQuote(ref.sinaPrefix(), ref.code()), ref.code()),
                e -> log.info("东财行情失败({}), 降级新浪: {}", ref.code(), e.getMessage()));
    }

    public List<KlineBar> kline(String code, String period, int limit) {
        StockRef ref = StockRef.from(code);
        String periodNorm = period == null ? "day" : period;
        int klt = switch (periodNorm) {
            case "day" -> 101;
            case "week" -> 102;
            case "month" -> 103;
            default -> throw new MarketDataException("INVALID_PERIOD", "period 仅支持 day/week/month");
        };
        int n = Math.max(KLINE_MIN_LIMIT, Math.min(limit <= 0 ? KLINE_DEFAULT_LIMIT : limit, MAX_LIMIT));
        String key = "k:" + ref.code() + ":" + klt + ":" + n;
        List<KlineBar> bars = cache.get(key);
        if (bars != null) {
            return bars;
        }
        String symbol = ref.sinaPrefix() + ref.code();
        bars = withFallback(
                () -> MarketDataParser.parseKline(eastmoney.kline(ref.secid(), klt, n)),
                () -> MarketDataParser.parseTencentKline(
                        tencent.kline(symbol, periodNorm, n), symbol, periodNorm),
                e -> log.info("东财K线失败({}), 降级腾讯: {}", ref.code(), e.getMessage()));
        cache.put(key, bars, props.getMarket().getCache().getKlineTtl());
        return bars;
    }

    public Financials financials(String code) {
        StockRef ref = StockRef.from(code);
        String key = "f:" + ref.code();
        Financials f = cache.get(key);
        if (f != null) {
            return f;
        }
        Quote q = quote(ref.code());
        var indicators =
                MarketDataParser.parseFinancialIndicators(eastmoney.financials(ref.secuCode()));
        Double pe = q.pe();
        Double pb = q.pb();
        if ((pe == null || pb == null) && !indicators.isEmpty()) {
            // 东财 f162/f167 偶发缺失，用财务指标自算估值
            Valuation v = estimateValuation(q.price(), indicators);
            if (pe == null) {
                pe = v.pe();
            }
            if (pb == null) {
                pb = v.pb();
            }
        }
        f = new Financials(ref.code(), q.name(), pe, pb, indicators);
        cache.put(key, f, props.getMarket().getCache().getFinancialsTtl());
        return f;
    }

    /**
     * 由财务指标估算估值：PB=价格/最新BPS；PE=价格/TTM EPS
     * （TTM = 最新累计EPS + 最近年报EPS − 上年同期累计EPS，中文财报EPS为年初至今累计值）。
     */
    private Valuation estimateValuation(double price, List<FinancialIndicator> indicators) {
        FinancialIndicator latest = indicators.get(0);
        Double pb = latest.bps() != null && latest.bps() > 0
                ? round2(price / latest.bps())
                : null;
        Double pe = null;
        FinancialIndicator annual = indicators.stream()
                .filter(i -> i.reportDate() != null && i.reportDate().endsWith("-12-31"))
                .findFirst()
                .orElse(null);
        if (annual != null && latest.eps() != null) {
            String rd = latest.reportDate();
            if (rd != null && rd.endsWith("-12-31")) {
                pe = round2(price / latest.eps());
            } else {
                String sameLastYear = sameLastYearOf(rd);
                if (sameLastYear != null) {
                    Double sameEps = indicators.stream()
                            .filter(i -> sameLastYear.equals(i.reportDate()))
                            .map(FinancialIndicator::eps)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(null);
                    if (annual.eps() != null && sameEps != null) {
                        double epsTtm = latest.eps() + annual.eps() - sameEps;
                        if (epsTtm > 0) {
                            pe = round2(price / epsTtm);
                        }
                    }
                }
            }
        }
        return new Valuation(pe, pb);
    }

    /** 由报告期推上一年同期（如 2026-06-30 → 2025-06-30）；格式异常返回 null，避免崩溃。 */
    private static String sameLastYearOf(String reportDate) {
        if (reportDate == null || reportDate.length() < 4) {
            return null;
        }
        String y = reportDate.substring(0, 4);
        if (!y.matches("\\d{4}")) {
            return null;
        }
        return (Integer.parseInt(y) - 1) + reportDate.substring(4);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private record Valuation(Double pe, Double pb) {}

    public List<NewsItem> news(String code, int limit) {
        StockRef ref = StockRef.from(code);
        int n = Math.max(1, Math.min(limit <= 0 ? NEWS_DEFAULT_LIMIT : limit, NEWS_MAX_LIMIT));
        String key = "n:" + ref.code() + ":" + n;
        List<NewsItem> items = cache.get(key);
        if (items != null) {
            return items;
        }
        String keyword = ref.code();
        try {
            String name = quote(ref.code()).name();
            if (name != null && !name.isBlank()) {
                keyword = name;
            }
        } catch (MarketDataException e) {
            log.warn("获取股票名称失败，新闻改用代码搜索: {}", ref.code());
        }
        items = MarketDataParser.parseNews(eastmoney.news(keyword, n));
        cache.put(key, items, props.getMarket().getCache().getNewsTtl());
        return items;
    }

    public MarketOverview overview() {
        String key = "o:indices";
        MarketOverview o = cache.get(key);
        if (o != null) {
            return o;
        }
        o = withFallback(
                () -> MarketDataParser.buildOverview(eastmoney.overview()),
                () -> MarketDataParser.buildSinaOverview(sina.rawIndices()),
                e -> log.info("东财指数失败({}), 降级新浪", e.getMessage()));
        cache.put(key, o, props.getMarket().getCache().getOverviewTtl());
        return o;
    }

    /** 供健康检查使用：绕过缓存，真实请求一次行情并计时。 */
    public long probeQuoteLatencyMs() {
        StockRef ref = StockRef.from("600519");
        long start = System.currentTimeMillis();
        fetchQuoteFresh(ref);
        return System.currentTimeMillis() - start;
    }

    /** 主源失败时降级到兜底源；两者均抛领域异常时由上层统一处理。 */
    private <T> T withFallback(
            Supplier<T> primary, Supplier<T> fallback, Consumer<MarketDataException> onPrimaryFailure) {
        try {
            return primary.get();
        } catch (MarketDataException e) {
            onPrimaryFailure.accept(e);
            return fallback.get();
        }
    }
}

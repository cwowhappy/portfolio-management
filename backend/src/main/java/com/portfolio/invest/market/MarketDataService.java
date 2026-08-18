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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 行情数据服务：缓存 + 限流 + 东财主源/新浪兜底编排。 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    private static final int MAX_LIMIT = 500;

    private final EastmoneyClient eastmoney;
    private final SinaClient sina;
    private final TencentClient tencent;
    private final InvestProperties props;
    private final TtlCache cache = new TtlCache();
    private final RateLimiter limiter;

    public MarketDataService(
            EastmoneyClient eastmoney, SinaClient sina, TencentClient tencent, InvestProperties props) {
        this.eastmoney = eastmoney;
        this.sina = sina;
        this.tencent = tencent;
        this.props = props;
        this.limiter = new RateLimiter(props.getMarket().getRateLimitPerSecond());
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
        acquire();
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
        try {
            acquire();
            q = MarketDataParser.parseQuote(eastmoney.quote(ref.secid()));
        } catch (MarketDataException e) {
            log.info("东财行情失败({}), 降级新浪: {}", ref.code(), e.getMessage());
            acquire();
            q = MarketDataParser.parseSinaQuote(sina.rawQuote(ref.sinaPrefix(), ref.code()), ref.code());
        }
        cache.put(key, q, props.getMarket().getCache().getQuoteTtl());
        return q;
    }

    public List<KlineBar> kline(String code, String period, int limit) {
        StockRef ref = StockRef.from(code);
        int klt = switch (period == null ? "day" : period) {
            case "day" -> 101;
            case "week" -> 102;
            case "month" -> 103;
            default -> throw new MarketDataException("INVALID_PERIOD", "period 仅支持 day/week/month");
        };
        String periodStr = switch (period == null ? "day" : period) {
            case "day" -> "day";
            case "week" -> "week";
            case "month" -> "month";
            default -> throw new MarketDataException("INVALID_PERIOD", "period 仅支持 day/week/month");
        };
        int n = Math.max(5, Math.min(limit <= 0 ? 120 : limit, MAX_LIMIT));
        String key = "k:" + ref.code() + ":" + klt + ":" + n;
        List<KlineBar> bars = cache.get(key);
        if (bars != null) {
            return bars;
        }
        try {
            acquire();
            bars = MarketDataParser.parseKline(eastmoney.kline(ref.secid(), klt, n));
        } catch (MarketDataException e) {
            log.info("东财K线失败({}), 降级腾讯: {}", ref.code(), e.getMessage());
            acquire();
            bars = MarketDataParser.parseTencentKline(
                    tencent.kline(ref.sinaPrefix() + ref.code(), periodStr, n),
                    ref.sinaPrefix() + ref.code(),
                    periodStr);
        }
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
        acquire();
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
                .filter(i -> i.reportDate().endsWith("-12-31"))
                .findFirst()
                .orElse(null);
        if (annual != null) {
            if (latest.reportDate().endsWith("-12-31") && latest.eps() != null) {
                pe = round2(price / latest.eps());
            } else {
                String sameLastYear =
                        (Integer.parseInt(latest.reportDate().substring(0, 4)) - 1)
                                + latest.reportDate().substring(4);
                Double sameEps = indicators.stream()
                        .filter(i -> i.reportDate().equals(sameLastYear))
                        .map(FinancialIndicator::eps)
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElse(null);
                if (latest.eps() != null && sameEps != null) {
                    double epsTtm = latest.eps() + annual.eps() - sameEps;
                    if (epsTtm > 0) {
                        pe = round2(price / epsTtm);
                    }
                }
            }
        }
        return new Valuation(pe, pb);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private record Valuation(Double pe, Double pb) {}

    public List<NewsItem> news(String code, int limit) {
        StockRef ref = StockRef.from(code);
        int n = Math.max(1, Math.min(limit <= 0 ? 10 : limit, 20));
        String key = "n:" + ref.code() + ":" + n;
        List<NewsItem> items = cache.get(key);
        if (items != null) {
            return items;
        }
        String keyword = ref.code();
        try {
            keyword = quote(ref.code()).name();
        } catch (MarketDataException e) {
            log.warn("获取股票名称失败，新闻改用代码搜索: {}", ref.code());
        }
        acquire();
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
        try {
            acquire();
            o = MarketDataParser.buildOverview(eastmoney.overview());
        } catch (MarketDataException e) {
            log.info("东财指数失败({}), 降级新浪", e.getMessage());
            acquire();
            o = MarketDataParser.buildSinaOverview(sina.rawIndices());
        }
        cache.put(key, o, props.getMarket().getCache().getOverviewTtl());
        return o;
    }

    /** 供健康检查使用：真实请求一次行情并计时。 */
    public long probeQuoteLatencyMs() {
        long start = System.currentTimeMillis();
        quote("600519");
        return System.currentTimeMillis() - start;
    }

    private void acquire() {
        if (!limiter.tryAcquire(2000)) {
            throw new MarketDataException("RATE_LIMITED", "行情请求过于频繁，请稍后再试");
        }
    }
}

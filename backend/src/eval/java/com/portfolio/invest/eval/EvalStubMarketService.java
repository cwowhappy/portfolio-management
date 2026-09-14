package com.portfolio.invest.eval;

import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketDataErrorCode;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.NewsItem;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.market.StockHit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评估桩行情服务：持有"当前题"的数据集（runner 逐题 {@link #inject} 覆盖），数据集未覆盖的
 * 调用抛 {@code UPSTREAM_UNAVAILABLE}（走生产错误降级路径，模型收到 error JSON）。
 *
 * <p>只替身行情数据门面，LLM 仍是真实 DeepSeek（评估对象）。逐题顺序执行无并发，volatile 足够。
 */
public class EvalStubMarketService implements MarketDataService {

    private volatile EvalStubData current = EvalStubData.empty();

    /** 注入当前题数据集（题目开始前调用；null 视为空数据集）。 */
    public void inject(EvalStubData data) {
        this.current = data == null ? EvalStubData.empty() : data;
    }

    @Override
    public List<StockHit> search(String query) {
        return current.searchOrNull();
    }

    @Override
    public Quote quote(String code) {
        Quote quote = current.quotesOrNull().get(normalized(code));
        if (quote == null) throw unavailable("quote " + code);
        return quote;
    }

    @Override
    public Map<String, Quote> quoteBatch(List<String> codes) {
        Map<String, Quote> result = new LinkedHashMap<>();
        for (String code : codes) {
            Quote hit = current.quotesOrNull().get(normalized(code));
            if (hit != null) result.put(normalized(code), hit);
        }
        return result;
    }

    @Override
    public List<KlineBar> kline(String code, String period, int limit) {
        List<KlineBar> bars = current.klinesOrNull().get(normalized(code));
        if (bars == null) throw unavailable("kline " + code);
        return bars.size() <= limit ? bars : bars.subList(0, limit);
    }

    @Override
    public Financials financials(String code) {
        Financials financials = current.financialsOrNull().get(normalized(code));
        if (financials == null) throw unavailable("financials " + code);
        return financials;
    }

    @Override
    public List<NewsItem> news(String code, int limit) {
        List<NewsItem> items = current.newsOrNull().get(normalized(code));
        if (items == null) throw unavailable("news " + code);
        return items.size() <= limit ? items : items.subList(0, limit);
    }

    @Override
    public MarketOverview overview() {
        MarketOverview overview = current.overview();
        if (overview == null) throw unavailable("overview");
        return overview;
    }

    @Override
    public long probeQuoteLatencyMs() {
        return 0;
    }

    /** 代码归一：只取 6 位数字（模型偶发带 sh 前缀/小写，别因格式让桩 miss）。 */
    private static String normalized(String code) {
        String digits = code == null ? "" : code.replaceAll("\\D", "");
        return digits.length() >= 6 ? digits.substring(digits.length() - 6) : code;
    }

    private static MarketDataException unavailable(String what) {
        return new MarketDataException(MarketDataErrorCode.UPSTREAM_UNAVAILABLE,
                "评估桩未提供数据: " + what);
    }
}

package com.portfolio.invest.web;

import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.NewsItem;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.market.StockHit;
import com.portfolio.invest.application.market.MarketDataService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行情 REST 接口（前端页面与 Agent 工具共用同一数据服务）。
 *
 * <p>A1 取舍（显式记录）：本控制器直接返回的 {@code domain.market.*} 均为纯数据值对象
 * （外部行情/搜索/财务的读模型，无行为），JSON 形状即对外契约，故不复刻一层 DTO；
 * 若未来这些对象引入行为或内部结构变化，再拆分应用层 DTO。
 */
@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final MarketDataService market;

    public MarketController(MarketDataService market) {
        this.market = market;
    }

    @GetMapping("/search")
    public List<StockHit> search(@RequestParam("q") String q) {
        return market.search(q);
    }

    @GetMapping("/quote/{code}")
    public Quote quote(@PathVariable String code) {
        return market.quote(code);
    }

    @GetMapping("/kline/{code}")
    public List<KlineBar> kline(
            @PathVariable String code,
            @RequestParam(defaultValue = "day") String period,
            @RequestParam(defaultValue = "120") int limit) {
        return market.kline(code, period, limit);
    }

    @GetMapping("/financials/{code}")
    public Financials financials(@PathVariable String code) {
        return market.financials(code);
    }

    @GetMapping("/news/{code}")
    public List<NewsItem> news(
            @PathVariable String code,
            @RequestParam(defaultValue = "10") int limit) {
        return market.news(code, limit);
    }

    @GetMapping("/overview")
    public MarketOverview overview() {
        return market.overview();
    }
}

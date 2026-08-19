package com.portfolio.invest.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.config.InvestProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 腾讯行情 K线兜底（东财 push2his 不可用时）。前复权，支持 day/week/month。 */
@Component
public class TencentClient {

    private final RestClient client;
    private final HttpExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public TencentClient(InvestProperties props, HttpClient http, RateLimiter limiter) {
        this.client = RestClientFactory.builder(http, props, "https://gu.qq.com/").build();
        this.executor = HttpExecutor.fromProps(limiter, props, "腾讯K线");
    }

    /** 测试注入：直接提供 RestClient，退避为 0 且不限流。 */
    TencentClient(RestClient client) {
        this.client = client;
        this.executor = HttpExecutor.forTests(new RateLimiter(1000), "腾讯K线");
    }

    /** symbol 形如 sh600519；period ∈ day/week/month。 */
    public JsonNode kline(String symbol, String period, int limit) {
        String p = switch (period) {
            case "week" -> "week";
            case "month" -> "month";
            default -> "day";
        };
        String url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param="
                + symbol + "," + p + ",,," + limit + ",qfq";
        String body = executor.execute(
                () -> client.get().uri(URI.create(url)).retrieve().body(String.class));
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new MarketDataException("BAD_RESPONSE", "腾讯K线响应解析失败", e);
        }
    }
}

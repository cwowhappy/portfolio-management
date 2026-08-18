package com.portfolio.invest.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.config.InvestProperties;
import java.net.URI;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 腾讯行情 K线兜底（东财 push2his 不可用时）。前复权，支持 day/week/month。 */
@Component
public class TencentClient {

    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public TencentClient(InvestProperties props) {
        this.client = RestClientFactory.builder(props, "https://gu.qq.com/").build();
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
        try {
            String body = client.get().uri(URI.create(url)).retrieve().body(String.class);
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new MarketDataException("UPSTREAM_UNAVAILABLE", "腾讯K线接口不可用: " + e.getMessage(), e);
        }
    }
}

package com.portfolio.invest.infrastructure.market;

import com.portfolio.invest.config.InvestProperties;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** RestClient 构造工具：基于共享 HttpClient 工厂 + 统一 UA/Referer（不依赖 Boot 自动配置）。 */
public final class RestClientFactory {

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private RestClientFactory() {}

    public static RestClient.Builder builder(HttpClient http, InvestProperties props, String referer) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.getMarket().getReadTimeout());
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", UA)
                .defaultHeader("Referer", referer);
    }
}

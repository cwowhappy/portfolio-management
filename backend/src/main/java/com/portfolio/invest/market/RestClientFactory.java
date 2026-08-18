package com.portfolio.invest.market;

import com.portfolio.invest.config.InvestProperties;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** RestClient 构造工具：JDK HttpClient 工厂 + 统一 UA/Referer（不依赖 Boot 自动配置）。 */
public final class RestClientFactory {

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private RestClientFactory() {}

    public static RestClient.Builder builder(InvestProperties props, String referer) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(props.getMarket().getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                // 东财等免费接口对 HTTP/2 偶发不兼容（空响应），强制 HTTP/1.1
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.getMarket().getReadTimeout());
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", UA)
                .defaultHeader("Referer", referer);
    }
}

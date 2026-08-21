package com.portfolio.invest.infrastructure.market;

import com.portfolio.invest.config.InvestProperties;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 行情能力域的共享基础设施装配：单个 JDK HttpClient（三客户端共用连接池/selector 线程）与单个 RateLimiter。
 * 位于 infrastructure.market 包（bean 装配属于本域，见 docs/backend-package-conventions.md）。
 */
@Configuration
public class MarketConfig {

    @Bean
    public HttpClient marketHttpClient(InvestProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(props.getMarket().getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                // 东财等免费接口对 HTTP/2 偶发不兼容（空响应），强制 HTTP/1.1
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean
    public RateLimiter marketRateLimiter(InvestProperties props) {
        return new RateLimiter(props.getMarket().getRateLimitPerSecond());
    }
}

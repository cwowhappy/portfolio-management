package com.portfolio.invest.infrastructure.market;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.market.MarketDataErrorCode;
import com.portfolio.invest.domain.market.MarketDataException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 新浪行情兜底（GBK 编码，需带 Referer）。超时来自 invest.market.*。 */
@Component
public class SinaClient {

    private static final Charset GBK = Charset.forName("GBK");

    private final RestClient client;
    private final HttpExecutor executor;

    @Autowired
    public SinaClient(InvestProperties props, HttpClient http, RateLimiter limiter) {
        this.client = RestClientFactory.builder(http, props, "https://finance.sina.com.cn/").build();
        this.executor = HttpExecutor.fromProps(limiter, props, "新浪");
    }

    /** 测试注入：直接提供 RestClient，退避为 0 且不限流。 */
    SinaClient(RestClient client) {
        this.client = client;
        this.executor = HttpExecutor.forTests(new RateLimiter(1000), "新浪");
    }

    /**
     * 新浪实时行情（兜底）。返回原始文本行：
     * var hq_str_sh600519="贵州茅台,1680.000,1685.000,...";
     */
    public String rawQuote(String sinaPrefix, String code) {
        String url = "https://hq.sinajs.cn/list=" + sinaPrefix + code;
        return fetch(url);
    }

    /** 指数速览兜底：s_sh000001=上证 s_sz399001=深成 s_sz399006=创业板。 */
    public String rawIndices() {
        return fetch("https://hq.sinajs.cn/list=s_sh000001,s_sz399001,s_sz399006");
    }

    private String fetch(String url) {
        return executor.execute(() -> {
            byte[] bytes = client.get().uri(URI.create(url)).retrieve().body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                throw new MarketDataException(MarketDataErrorCode.UPSTREAM_UNAVAILABLE, "新浪接口返回空");
            }
            return new String(bytes, GBK);
        });
    }
}

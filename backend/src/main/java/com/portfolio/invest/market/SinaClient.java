package com.portfolio.invest.market;

import com.portfolio.invest.config.InvestProperties;
import java.net.URI;
import java.nio.charset.Charset;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 新浪行情兜底（GBK 编码，需带 Referer）。超时来自 invest.market.*。 */
@Component
public class SinaClient {

    private static final Charset GBK = Charset.forName("GBK");

    private final RestClient client;

    public SinaClient(InvestProperties props) {
        this.client = RestClientFactory.builder(props, "https://finance.sina.com.cn/").build();
    }

    /**
     * 新浪实时行情（兜底）。返回原始文本行：
     * var hq_str_sh600519="贵州茅台,1680.000,1685.000,...";
     */
    public String rawQuote(String sinaPrefix, String code) {
        String url = "https://hq.sinajs.cn/list=" + sinaPrefix + code;
        try {
            byte[] bytes = client.get().uri(URI.create(url)).retrieve().body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                throw new MarketDataException("UPSTREAM_UNAVAILABLE", "新浪接口返回空");
            }
            return new String(bytes, GBK);
        } catch (MarketDataException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketDataException("UPSTREAM_UNAVAILABLE", "新浪接口不可用: " + e.getMessage(), e);
        }
    }
}

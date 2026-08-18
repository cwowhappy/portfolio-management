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
        return fetch(url);
    }

    /** 指数速览兜底：s_sh000001=上证 s_sz399001=深成 s_sz399006=创业板。 */
    public String rawIndices() {
        return fetch("https://hq.sinajs.cn/list=s_sh000001,s_sz399001,s_sz399006");
    }

    private String fetch(String url) {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                byte[] bytes = client.get().uri(URI.create(url)).retrieve().body(byte[].class);
                if (bytes == null || bytes.length == 0) {
                    throw new MarketDataException("UPSTREAM_UNAVAILABLE", "新浪接口返回空");
                }
                return new String(bytes, GBK);
            } catch (MarketDataException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                if (attempt < 2) {
                    try {
                        Thread.sleep(300L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new MarketDataException("UPSTREAM_UNAVAILABLE", "新浪接口不可用: " + last.getMessage(), last);
    }
}

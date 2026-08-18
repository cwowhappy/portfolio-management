package com.portfolio.invest.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.portfolio.invest.config.InvestProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 东方财富公开接口客户端（仅负责 HTTP 与原始 JSON 返回，解析见 MarketDataParser）。 */
@Component
public class EastmoneyClient {

    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 超时来自 invest.market.connect-timeout / read-timeout。 */
    public EastmoneyClient(InvestProperties props) {
        this.client = RestClientFactory.builder(props, "https://quote.eastmoney.com/").build();
    }

    /** 实时行情。 */
    public JsonNode quote(String secid) {
        String url = "https://push2.eastmoney.com/api/qt/stock/get?secid=" + secid
                + "&fields=f43,f44,f45,f46,f47,f48,f57,f58,f60,f86,f162,f167,f169,f170&fltt=2&invt=2";
        return get(url);
    }

    /** K线：klt 101=日 102=周 103=月。 */
    public JsonNode kline(String secid, int klt, int limit) {
        String url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=" + secid
                + "&klt=" + klt + "&fqt=1&lmt=" + limit
                + "&end=20500101&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58";
        return get(url);
    }

    /** 股票搜索。 */
    public JsonNode search(String query) {
        String url = "https://searchapi.eastmoney.com/api/suggest/get?input="
                + encode(query) + "&type=14&token=D43BF722C8E33BDC906FB84D85E326E8&count=10";
        return get(url);
    }

    /** 财务主要指标（F10 数据中台）。 */
    public JsonNode financials(String secuCode) {
        String filter = "(SECUCODE%3D%22" + secuCode + "%22)";
        String url = "https://datacenter.eastmoney.com/securities/api/data/v1/get"
                + "?reportName=RPT_F10_FINANCE_MAINFINADATA"
                + "&columns=REPORT_DATE,EPSJB,BPS,TOTALOPERATEREVE,PARENTNETPROFIT,ROEJQ,XSMLL"
                + "&filter=" + filter
                + "&pageNumber=1&pageSize=8&sortTypes=-1&sortColumns=REPORT_DATE&source=HSF10&client=PC";
        return get(url);
    }

    /** 个股新闻搜索（JSONP）。 */
    public JsonNode news(String keyword, int limit) {
        try {
            ObjectNode searchScope = mapper.createObjectNode();
            searchScope.put("searchScope", "default")
                    .put("sort", "default")
                    .put("pageIndex", 1)
                    .put("pageSize", limit)
                    .put("preTag", "")
                    .put("postTag", "");
            ObjectNode cms = mapper.createObjectNode();
            cms.set("cmsArticleWebOld", searchScope);
            ObjectNode root = mapper.createObjectNode();
            root.put("uid", "")
                    .put("keyword", keyword)
                    .put("client", "web")
                    .put("clientType", "web")
                    .put("clientVersion", "curr");
            root.set("type", mapper.createArrayNode().add("cmsArticleWebOld"));
            root.set("param", cms);
            String url = "https://search-api-web.eastmoney.com/search/jsonp?cb=cb&param="
                    + encode(mapper.writeValueAsString(root));
            String body = getText(url);
            int start = body.indexOf('(');
            int end = body.lastIndexOf(')');
            if (start < 0 || end <= start) {
                throw new MarketDataException("BAD_RESPONSE", "新闻接口响应格式异常");
            }
            return mapper.readTree(body.substring(start + 1, end));
        } catch (IOException e) {
            throw new MarketDataException("BAD_RESPONSE", "新闻接口解析失败", e);
        }
    }

    /** 指数列表。 */
    public JsonNode overview() {
        String url = "https://push2.eastmoney.com/api/qt/ulist.np/get"
                + "?secids=1.000001,0.399001,0.399006&fields=f2,f3,f4,f12,f14&fltt=2";
        return get(url);
    }

    private JsonNode get(String url) {
        String body = getText(url);
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new MarketDataException("BAD_RESPONSE", "行情接口响应解析失败", e);
        }
    }

    private String getText(String url) {
        try {
            // 注意：不能用 uri(String)（URI 模板展开会把 % 二次编码），必须直传 URI
            return client.get().uri(URI.create(url)).retrieve().body(String.class);
        } catch (Exception e) {
            throw new MarketDataException("UPSTREAM_UNAVAILABLE", "东方财富接口不可用: " + e.getMessage(), e);
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

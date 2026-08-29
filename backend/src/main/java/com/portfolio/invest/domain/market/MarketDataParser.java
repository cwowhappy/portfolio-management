package com.portfolio.invest.domain.market;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 纯函数解析器：JsonNode → DTO，全部可离线单测（fixture 驱动）。 */
public final class MarketDataParser {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");

    private MarketDataParser() {}

    /** 东财实时行情（fltt=2 下数值均为浮点，异常值为 "-" 字符串）。 */
    public static Quote parseQuote(JsonNode root) {
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "行情数据为空");
        }
        double price = data.path("f43").asDouble();
        if (price <= 0) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "行情价格为空或无效");
        }
        long f86 = data.path("f86").asLong();
        String time = f86 > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochSecond(f86), MARKET_ZONE).format(TIME_FMT)
                : "";
        double amount = numOrZero(data.path("f48"));
        long volume = normalizeVolume(data.path("f47").asLong(), amount, price);
        return new Quote(
                data.path("f57").asText(""),
                data.path("f58").asText(""),
                price,
                numOrZero(data.path("f169")),
                numOrZero(data.path("f170")),
                numOrZero(data.path("f46")),
                numOrZero(data.path("f44")),
                numOrZero(data.path("f45")),
                numOrZero(data.path("f60")),
                volume,
                amount,
                nullable(data.path("f162")),
                nullable(data.path("f167")),
                time);
    }

    /**
     * 东财 f47 单位不稳定（有时手、有时股），以成交额反推归一化为股：
     * 取 |f47 - amount/price| 与 |f47*100 - amount/price| 中更接近者。
     */
    public static long normalizeVolume(long raw, double amount, double price) {
        if (raw <= 0 || amount <= 0 || price <= 0) {
            return raw;
        }
        double estShares = amount / price;
        double asShares = Math.abs(raw - estShares);
        double asLots = Math.abs(raw * 100.0 - estShares);
        return asShares <= asLots ? raw : raw * 100;
    }

    /** 新浪行情兜底解析。 */
    public static Quote parseSinaQuote(String raw, String code) {
        int start = raw.indexOf('"');
        int end = raw.lastIndexOf('"');
        if (start < 0 || end <= start) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "新浪行情响应格式异常");
        }
        String[] f = raw.substring(start + 1, end).split(",");
        if (f.length < 10) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "新浪行情字段不足");
        }
        double price = numOrZero(f[3]);
        double prevClose = numOrZero(f[2]);
        if (price <= 0) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "新浪行情价格无效");
        }
        String time = f.length > 31 ? f[30] + " " + f[31] : "";
        return new Quote(
                code,
                f[0],
                price,
                round2(price - prevClose),
                prevClose > 0 ? round2((price - prevClose) / prevClose * 100) : 0,
                numOrZero(f[1]),
                numOrZero(f[4]),
                numOrZero(f[5]),
                prevClose,
                f.length > 8 ? parseLongOrZero(f[8]) : 0,
                f.length > 9 ? numOrZero(f[9]) : 0,
                null,
                null,
                time);
    }

    /** K线：klines 为 "date,open,close,high,low,volume,amount,amplitude" 字符串数组。 */
    public static List<KlineBar> parseKline(JsonNode root) {
        JsonNode klines = root.path("data").path("klines");
        if (klines.isMissingNode() || klines.isNull()) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "K线数据为空");
        }
        List<KlineBar> bars = new ArrayList<>();
        for (JsonNode line : klines) {
            String[] f = line.asText().split(",");
            if (f.length < 8) {
                continue;
            }
            bars.add(new KlineBar(
                    f[0],
                    numOrZero(f[1]),
                    numOrZero(f[2]),
                    numOrZero(f[3]),
                    numOrZero(f[4]),
                    parseLongOrZero(f[5]) * 100, // 东财K线成交量为手，统一转为股
                    numOrZero(f[6]),
                    numOrZero(f[7])));
        }
        if (bars.isEmpty()) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "K线数据为空");
        }
        return bars;
    }

    /**
     * 腾讯 K线兜底：data.{symbol}.{qfq+period} 为 [date, open, close, high, low, volume(手)] 数组，
     * 成交量统一转股，按日期升序。腾讯仅返回 6 列（无成交额/振幅），amount 与 amplitudePct 置 0。
     */
    public static List<KlineBar> parseTencentKline(JsonNode root, String symbol, String period) {
        JsonNode data = root.path("data").path(symbol);
        JsonNode arr = data.path("qfq" + period);
        if (arr.isMissingNode() || !arr.isArray()) {
            arr = data.path(period);
        }
        if (!arr.isArray()) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "腾讯K线数据为空");
        }
        List<KlineBar> bars = new ArrayList<>();
        for (JsonNode row : arr) {
            if (!row.isArray() || row.size() < 6) {
                continue;
            }
            bars.add(new KlineBar(
                    row.get(0).asText(),
                    numOrZero(row.get(1)),
                    numOrZero(row.get(2)),
                    numOrZero(row.get(3)),
                    numOrZero(row.get(4)),
                    parseLongOrZero(row.get(5).asText()) * 100,
                    row.size() > 6 ? numOrZero(row.get(6)) : 0,
                    row.size() > 7 ? numOrZero(row.get(7)) : 0));
        }
        if (bars.isEmpty()) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "腾讯K线数据为空");
        }
        bars.sort(Comparator.comparing(KlineBar::date));
        return bars;
    }

    /** 股票搜索（过滤指数/板块/基金/债券/期货等非A股条目）。 */
    public static List<StockHit> parseSearch(JsonNode root) {
        JsonNode data = root.path("QuotationCodeTable").path("Data");
        List<StockHit> hits = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                String typeName = item.path("SecurityTypeName").asText("");
                if (typeName.contains("指数") || typeName.contains("板块") || typeName.contains("基金")
                        || typeName.contains("债") || typeName.contains("期货")) {
                    continue;
                }
                String code = item.path("Code").asText("");
                String mkt = item.path("MktNum").asText("1");
                hits.add(new StockHit(
                        code,
                        item.path("Name").asText(""),
                        mkt,
                        marketNameOf(code, mkt)));
            }
        }
        return hits;
    }

    /** 市场名称：以代码前缀为准（与 StockRef 规则一致），北交所单独识别。 */
    private static String marketNameOf(String code, String mkt) {
        if (code.startsWith("4") || code.startsWith("8")) {
            return "北交所";
        }
        if (code.startsWith("6") || code.startsWith("9") || "1".equals(mkt)) {
            return "沪市";
        }
        return "深市";
    }

    /** 财务指标序列（东财 F10 数据中台）。 */
    public static List<FinancialIndicator> parseFinancialIndicators(JsonNode root) {
        JsonNode data = root.path("result").path("data");
        if (data.isMissingNode() || data.isNull() || !data.isArray()) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "财务数据为空");
        }
        List<FinancialIndicator> list = new ArrayList<>();
        for (JsonNode item : data) {
            String date = item.path("REPORT_DATE").asText("");
            if (date.length() >= 10) {
                date = date.substring(0, 10);
            }
            list.add(new FinancialIndicator(
                    date,
                    nullable(item.path("EPSJB")),
                    nullable(item.path("BPS")),
                    nullable(item.path("TOTALOPERATEREVE")),
                    nullable(item.path("PARENTNETPROFIT")),
                    nullable(item.path("ROEJQ")),
                    nullable(item.path("XSMLL"))));
        }
        return list;
    }

    /** 新闻（JSONP 解包后的 JSON）。 */
    public static List<NewsItem> parseNews(JsonNode root) {
        JsonNode data = root.path("result").path("cmsArticleWebOld");
        List<NewsItem> list = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                list.add(new NewsItem(
                        stripHtml(item.path("title").asText("")),
                        abbreviate(stripHtml(item.path("content").asText("")), 120),
                        item.path("mediaName").asText(""),
                        item.path("date").asText(""),
                        item.path("url").asText("")));
            }
        }
        return list;
    }

    /** 指数列表。 */
    public static List<IndexQuote> parseOverview(JsonNode root) {
        JsonNode diff = root.path("data").path("diff");
        List<IndexQuote> list = new ArrayList<>();
        if (diff.isArray()) {
            for (JsonNode item : diff) {
                list.add(new IndexQuote(
                        item.path("f12").asText(""),
                        item.path("f14").asText(""),
                        numOrZero(item.path("f2")),
                        numOrZero(item.path("f4")),
                        numOrZero(item.path("f3"))));
            }
        }
        return list;
    }

    public static MarketOverview buildOverview(JsonNode root) {
        List<IndexQuote> indices = parseOverview(root);
        if (indices.isEmpty()) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "指数数据为空");
        }
        return new MarketOverview(
                LocalDateTime.now(MARKET_ZONE).format(TIME_FMT), indices);
    }

    /** 新浪指数兜底：多行 var hq_str_s_sh000001="上证指数,3990.30,7.65,0.19,..."; */
    public static MarketOverview buildSinaOverview(String raw) {
        List<IndexQuote> list = new ArrayList<>();
        String[] lines = raw.split(";");
        for (String line : lines) {
            int start = line.indexOf('"');
            int end = line.lastIndexOf('"');
            if (start < 0 || end <= start) {
                continue;
            }
            String[] f = line.substring(start + 1, end).split(",");
            if (f.length < 4) {
                continue;
            }
            list.add(new IndexQuote(
                    extractIndexCode(line, start), f[0], numOrZero(f[1]), numOrZero(f[2]), numOrZero(f[3])));
        }
        if (list.isEmpty()) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "新浪指数数据为空");
        }
        return new MarketOverview(LocalDateTime.now(MARKET_ZONE).format(TIME_FMT), list);
    }

    /** 从 var hq_str_s_sh000001 提取指数代码 000001（去掉 sh/sz/bj 前缀）。 */
    private static String extractIndexCode(String line, int firstQuote) {
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return "";
        }
        String varName = line.substring(0, eq).trim();
        int underscore = varName.lastIndexOf('_');
        String id = underscore >= 0 ? varName.substring(underscore + 1) : varName;
        return id.length() > 2 ? id.substring(2) : id;
    }

    public static Financials buildFinancials(String code, String name, Double pe, Double pb, JsonNode root) {
        return new Financials(code, name, pe, pb, parseFinancialIndicators(root));
    }

    private static double numOrZero(JsonNode n) {
        return n.asDouble();
    }

    private static double numOrZero(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseLongOrZero(String s) {
        try {
            return (long) Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static Double nullable(JsonNode n) {
        if (n == null || n.isNull() || !n.isNumber()) {
            return null;
        }
        return n.asDouble();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String stripHtml(String s) {
        return s.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replace("&amp;", "&").trim();
    }

    private static String abbreviate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}

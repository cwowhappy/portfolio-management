package com.portfolio.invest.domain.market;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 纯函数解析器：已规范化中间结构（{@link RawQuote}/{@link RawIndexQuote}/{@link RawStockHit}/
 * {@link RawKlineBar}/{@link RawFinancialIndicator}/{@link RawNewsItem}）/ wire 文本 → DTO，
 * 全部可离线单测（fixture 驱动）。东财 JSON 字段码与路径导航已全量下沉
 * infrastructure/market/EastmoneyFormat（2026-10-15 审查 §五 L2）；本类残留的
 * JsonNode 入参仅为腾讯 K线（parseTencentKline），属 L2-二期（新浪/腾讯文本兜底解析同批另计）。 */
public final class MarketDataParser {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");

    private MarketDataParser() {}

    /**
     * 实时行情解析（供应商无关）：接收 infrastructure 映射好的 {@link RawQuote} 中间结构
     * （东财 fltt=2 字段码映射见 infrastructure/market/EastmoneyFormat——2026-10-12 字段码
     * 下沉，本类不再携带任何 f43/f57 类 wire format 知识）。价格校验、成交量归一化、
     * 时间戳格式化等纯逻辑不变，fixture 可离线单测（fixture 驱动）。
     */
    public static Quote parseQuote(RawQuote raw) {
        if (raw == null) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "行情数据为空");
        }
        double price = raw.price();
        if (price <= 0) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "行情价格为空或无效");
        }
        long epochSeconds = raw.epochSeconds();
        String time = epochSeconds > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), MARKET_ZONE).format(TIME_FMT)
                : "";
        long volume = normalizeVolume(raw.volume(), raw.amount(), price);
        return new Quote(
                raw.code(),
                raw.name(),
                price,
                raw.change(),
                raw.changePct(),
                raw.open(),
                raw.high(),
                raw.low(),
                raw.prevClose(),
                volume,
                raw.amount(),
                raw.pe(),
                raw.pb(),
                time);
    }

    /**
     * 上游成交量单位不稳定（有时手、有时股），以成交额反推归一化为股：
     * 取 |raw - amount/price| 与 |raw*100 - amount/price| 中更接近者。
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

    /** 腾讯实时行情（主源）：v_sh600519="1~名称~代码~价~昨收~今开~量(手)~...~时间~涨跌~涨跌幅~高~低~...~量(手)~额(万)~换手~PE(TTM)~...~PB~..."（~ 分隔）。 */
    public static Quote parseTencentQuote(String raw, String code) {
        int start = raw.indexOf('"');
        int end = raw.lastIndexOf('"');
        if (start < 0 || end <= start) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "腾讯行情响应格式异常");
        }
        String[] f = raw.substring(start + 1, end).split("~", -1);
        if (f.length < 47) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "腾讯行情字段不足");
        }
        double price = numOrZero(f[3]);
        if (price <= 0) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "腾讯行情价格无效");
        }
        return new Quote(
                code,
                f[1],
                price,
                numOrZero(f[31]),
                numOrZero(f[32]),
                numOrZero(f[5]),
                numOrZero(f[33]),
                numOrZero(f[34]),
                numOrZero(f[4]),
                parseLongOrZero(f[6]) * 100,          // 手 → 股
                numOrZero(f[37]) * 10000,             // 万元 → 元
                nullablePos(f[39]),                   // PE(TTM)
                nullablePos(f[46]),                   // PB
                formatTencentTime(f[30]));
    }

    /** 腾讯指数速览（主源）：多行 v_s_sh000001="1~上证指数~000001~点位~涨跌~涨跌幅~..."; */
    public static MarketOverview buildTencentOverview(String raw) {
        List<IndexQuote> list = new ArrayList<>();
        for (String line : raw.split(";")) {
            int start = line.indexOf('"');
            int end = line.lastIndexOf('"');
            if (start < 0 || end <= start) {
                continue;
            }
            String[] f = line.substring(start + 1, end).split("~");
            if (f.length < 6) {
                continue;
            }
            list.add(new IndexQuote(f[2], f[1], numOrZero(f[3]), numOrZero(f[4]), numOrZero(f[5])));
        }
        if (list.isEmpty()) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "腾讯指数数据为空");
        }
        return new MarketOverview(LocalDateTime.now(MARKET_ZONE).format(TIME_FMT), list);
    }

    /**
     * K线解析（供应商无关）：接收 infrastructure 映射好的 {@link RawKlineBar} 中间结构
     * （东财 data.klines CSV 行拆分见 infrastructure/market/EastmoneyFormat——2026-10-15
     * L2 字段码下沉）。成交量手→股归一化（与 {@link #parseTencentKline} 同口径）与
     * 空校验不变：全部行被跳过/空列表抛 BAD_RESPONSE「K线数据为空」（文案与触发口径
     * 与下沉前一致——缺失判断在 extract 侧，空列表判断在本方法）。
     */
    public static List<KlineBar> parseKline(List<RawKlineBar> raws) {
        if (raws == null || raws.isEmpty()) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "K线数据为空");
        }
        List<KlineBar> bars = new ArrayList<>();
        for (RawKlineBar raw : raws) {
            bars.add(new KlineBar(
                    raw.date(),
                    raw.open(),
                    raw.close(),
                    raw.high(),
                    raw.low(),
                    raw.volume() * 100, // 东财K线成交量为手，统一转为股
                    raw.amount(),
                    raw.amplitude()));
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

    /**
     * 股票搜索（供应商无关）：接收 infrastructure 过滤映射好的 {@link RawStockHit} 中间结构
     * （东财 QuotationCodeTable 字段与非 A 股过滤——SecurityTypeName 判定为 wire 知识——见
     * infrastructure/market/EastmoneyFormat，2026-10-15 L2 下沉）。本方法只做市场名归一化：
     * 统一走 StockRef.Exchange 判定（B-25，收敛代码前缀与 MktNum 兜底）。
     */
    public static List<StockHit> parseSearch(List<RawStockHit> raws) {
        List<StockHit> hits = new ArrayList<>();
        if (raws == null) {
            return hits;
        }
        for (RawStockHit raw : raws) {
            hits.add(new StockHit(
                    raw.code(),
                    raw.name(),
                    raw.mktNum(),
                    marketNameOf(raw.code(), raw.mktNum())));
        }
        return hits;
    }

    /** 市场名称：统一走 StockRef.Exchange 判定（B-25，收敛代码前缀与 MktNum 兜底）。 */
    private static String marketNameOf(String code, String mkt) {
        return StockRef.Exchange.of(code, mkt).displayName();
    }

    /**
     * 财务指标序列（供应商无关）：接收 infrastructure 映射好的 {@link RawFinancialIndicator}
     * 中间结构（东财 F10 数据中台 result.data[] 字段码见 infrastructure/market/EastmoneyFormat
     * ——2026-10-15 L2 下沉）。报告期截断为 yyyy-MM-dd 的归一化不变；源缺失/非数值的
     * 可空字段已由 extract 侧归 null（与下沉前 nullable(JsonNode) 同口径），空列表原样
     * 返回空（与下沉前空 data 数组口径一致，不抛异常）。
     */
    public static List<FinancialIndicator> parseFinancialIndicators(List<RawFinancialIndicator> raws) {
        List<FinancialIndicator> list = new ArrayList<>();
        if (raws == null) {
            return list;
        }
        for (RawFinancialIndicator raw : raws) {
            String date = raw.reportDate();
            if (date != null && date.length() >= 10) {
                date = date.substring(0, 10);
            }
            list.add(new FinancialIndicator(
                    date,
                    raw.eps(),
                    raw.bps(),
                    raw.revenue(),
                    raw.profit(),
                    raw.roe(),
                    raw.grossMargin()));
        }
        return list;
    }

    /**
     * 新闻（供应商无关）：接收 infrastructure 映射好的 {@link RawNewsItem} 中间结构
     * （东财 result.cmsArticleWebOld 字段见 infrastructure/market/EastmoneyFormat——
     * 2026-10-15 L2 下沉）。HTML 剥离与摘要截断（120 字加省略号）等文本归一化不变。
     */
    public static List<NewsItem> parseNews(List<RawNewsItem> raws) {
        List<NewsItem> list = new ArrayList<>();
        if (raws == null) {
            return list;
        }
        for (RawNewsItem raw : raws) {
            list.add(new NewsItem(
                    stripHtml(raw.title()),
                    abbreviate(stripHtml(raw.content()), 120),
                    raw.media(),
                    raw.date(),
                    raw.url()));
        }
        return list;
    }

    /** 指数列表（供应商无关）：接收 infrastructure 映射好的 {@link RawIndexQuote} 中间结构
     *  （东财 push2 data.diff 的 f12/f14/f2/f4/f3 字段码见 infrastructure/market/EastmoneyFormat
     *  ——2026-10-15 L2 下沉）。本方法为 1:1 映射，无归一化。 */
    public static List<IndexQuote> parseOverview(List<RawIndexQuote> raws) {
        List<IndexQuote> list = new ArrayList<>();
        if (raws == null) {
            return list;
        }
        for (RawIndexQuote raw : raws) {
            list.add(new IndexQuote(
                    raw.code(),
                    raw.name(),
                    raw.price(),
                    raw.change(),
                    raw.changePct()));
        }
        return list;
    }

    public static MarketOverview buildOverview(List<RawIndexQuote> raws) {
        List<IndexQuote> indices = parseOverview(raws);
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

    /** 财务总装（供应商无关）：PE/PB 为行情侧入参，指标序列收 {@link RawFinancialIndicator} 中间结构。 */
    public static Financials buildFinancials(String code, String name, Double pe, Double pb,
                                             List<RawFinancialIndicator> raws) {
        return new Financials(code, name, pe, pb, parseFinancialIndicators(raws));
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

    /** 腾讯时间戳 yyyyMMddHHmmss → "yyyy-MM-dd HH:mm"；空或畸形返回 ""。 */
    private static String formatTencentTime(String s) {
        if (s == null || !s.matches("\\d{14}")) {
            return "";
        }
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).format(TIME_FMT);
        } catch (DateTimeParseException e) {
            return ""; // 14 位但语义非法（如月 99）：与畸形返回空串同口径，不穿透降级链（issue #53）
        }
    }

    /** 正数可空字段（PE/PB）：空串/非数值/≤0 → null。 */
    private static Double nullablePos(String s) {
        try {
            double v = Double.parseDouble(s.trim());
            return v > 0 ? v : null;
        } catch (Exception e) {
            return null;
        }
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

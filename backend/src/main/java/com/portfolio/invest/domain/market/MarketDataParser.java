package com.portfolio.invest.domain.market;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 纯函数解析器（供应商无关）：{@link RawQuote}/{@link RawIndexQuote}/{@link RawStockHit}/
 * {@link RawKlineBar}/{@link RawFinancialIndicator}/{@link RawNewsItem} 已规范化中间结构 → DTO，
 * 全部可离线单测（fixture 驱动）。东财 JSON 字段码与路径导航已全量下沉
 * infrastructure/market/EastmoneyFormat（2026-10-15 审查 §五 L2）；新浪/腾讯文本协议解析
 * （含腾讯 K线 JSON 帧）已归各自 infrastructure/market/SinaFormat、TencentFormat
 * （2026-10-16 L2-二期）——本类为零供应商 wire 知识的归一化层。 */
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

    /**
     * K线解析（供应商无关）：接收 infrastructure 映射好的 {@link RawKlineBar} 中间结构
     * （东财 data.klines CSV 行拆分见 infrastructure/market/EastmoneyFormat——2026-10-15
     * L2 字段码下沉）。成交量手→股归一化（与腾讯 K线同口径）与空校验不变：全部行被跳过/
     * 空列表抛 BAD_RESPONSE「K线数据为空」（文案与触发口径与下沉前一致——缺失判断在 extract
     * 侧，空列表判断在本方法）。
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
     * ——2026-10-15 L2 下沉）。本方法为 1:1 映射，无归一化。 */
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

    /** 财务总装（供应商无关）：PE/PB 为行情侧入参，指标序列收 {@link RawFinancialIndicator} 中间结构。 */
    public static Financials buildFinancials(String code, String name, Double pe, Double pb,
                                             List<RawFinancialIndicator> raws) {
        return new Financials(code, name, pe, pb, parseFinancialIndicators(raws));
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

package com.portfolio.invest.infrastructure.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.portfolio.invest.domain.market.FinancialIndicator;
import com.portfolio.invest.domain.market.IndexQuote;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketDataErrorCode;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.MarketDataParser;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.NewsItem;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.market.RawFinancialIndicator;
import com.portfolio.invest.domain.market.RawIndexQuote;
import com.portfolio.invest.domain.market.RawKlineBar;
import com.portfolio.invest.domain.market.RawNewsItem;
import com.portfolio.invest.domain.market.RawQuote;
import com.portfolio.invest.domain.market.RawStockHit;
import com.portfolio.invest.domain.market.StockHit;
import java.util.ArrayList;
import java.util.List;

/**
 * 东财 wire format 知识单点（2026-10-12 审查 §三.7 字段码下沉 + 2026-10-15 审查 §五 L2
 * 全量收口）：实时行情/搜索/K线/财务指标/新闻/指数速览六类帧的字段码（f43/f57/f58、f12/f14/
 * f2/f4/f3、QuotationCodeTable、data.klines、REPORT_DATE/EPSJB...、cmsArticleWebOld...）与
 * JSON 路径导航只存在本类——wire → {@code Raw*} 已规范化中间结构的映射在此完成，domain
 * {@link MarketDataParser} 只解析中间结构（domain 零供应商知识、零 infrastructure 依赖，
 * ArchUnit 白名单不变）。主源切换时只需替换本映射层。
 *
 * <p>数值口径（与下沉前 domain 解析逐字一致）：fltt=2 下数值均为浮点，异常值为 "-" 字符串
 * （数值字段 asDouble 归 0，PE/PB nullable 归 null）；f47 成交量单位不稳定，归一化仍在
 * domain {@code normalizeVolume} 承担；东财 K线成交量为手，手→股归一化仍在 domain
 * {@code parseKline} 承担（与腾讯 K线口径一致）；非 A 股搜索条目的 SecurityTypeName
 * 判定（指数/板块/基金/债/期货）为本类固有 wire 知识，过滤后只放行 A 股候选。
 *
 * <p>异常语义与下沉前逐字保持：data/klines/result.data 缺失、null 或形态非法抛
 * BAD_RESPONSE 的文案与口径不变（klines 缺失在 extractKline、全行跳过致空在 domain
 * parseKline、result.data 形态非法在 extractFinancials——经 {@code parse*} 组合方法透出
 * 的触发条件与下沉前完全一致）。
 */
public final class EastmoneyFormat {

    /** 最新价（fltt=2 浮点）。 */
    static final String F43_PRICE = "f43";
    /** 成交量（单位不稳定：手/股，经 domain normalizeVolume 以成交额反推归一）。 */
    static final String F47_VOLUME = "f47";
    /** 成交额。 */
    static final String F48_AMOUNT = "f48";
    /** 今开。 */
    static final String F46_OPEN = "f46";
    /** 最高。 */
    static final String F44_HIGH = "f44";
    /** 最低。 */
    static final String F45_LOW = "f45";
    /** 昨收。 */
    static final String F60_PREV_CLOSE = "f60";
    /** 标的代码。 */
    static final String F57_CODE = "f57";
    /** 标的名称。 */
    static final String F58_NAME = "f58";
    /** 行情时间戳（epoch 秒）。 */
    static final String F86_TIMESTAMP = "f86";
    /** 涨跌额。 */
    static final String F169_CHANGE = "f169";
    /** 涨跌幅。 */
    static final String F170_CHANGE_PCT = "f170";
    /** 市盈率（可空，异常值 "-"）。 */
    static final String F162_PE = "f162";
    /** 市净率（可空，异常值 "-"）。 */
    static final String F167_PB = "f167";

    /** 指数速览（push2 接口）节点与字段码：data.diff[] 的 f12/f14/f2/f4/f3。 */
    static final String NODE_DATA = "data";
    static final String NODE_DIFF = "diff";
    static final String F12_CODE = "f12";
    static final String F14_NAME = "f14";
    static final String F2_PRICE = "f2";
    static final String F4_CHANGE = "f4";
    static final String F3_CHANGE_PCT = "f3";

    /** 股票搜索（QuotationCodeTable）节点与字段：Data[] 的 Code/Name/MktNum/SecurityTypeName。 */
    static final String NODE_QUOTATION_CODE_TABLE = "QuotationCodeTable";
    static final String NODE_DATA_ITEMS = "Data";
    static final String FIELD_CODE = "Code";
    static final String FIELD_NAME = "Name";
    static final String FIELD_MKT_NUM = "MktNum";
    static final String FIELD_SECURITY_TYPE_NAME = "SecurityTypeName";

    /** K线节点：data.klines 为 "date,open,close,high,low,volume,amount,amplitude,..." CSV 行数组。 */
    static final String NODE_KLINES = "klines";

    /** 财务指标（F10 数据中台）节点与字段码：result.data[] 的报告期/每股收益/每股净资产/
     *  营业总收入/归母净利润/加权 ROE/毛利率。 */
    static final String NODE_RESULT = "result";
    static final String FIELD_REPORT_DATE = "REPORT_DATE";
    static final String FIELD_EPSJB = "EPSJB";
    static final String FIELD_BPS = "BPS";
    static final String FIELD_TOTALOPERATEREVE = "TOTALOPERATEREVE";
    static final String FIELD_PARENTNETPROFIT = "PARENTNETPROFIT";
    static final String FIELD_ROEJQ = "ROEJQ";
    static final String FIELD_XSMLL = "XSMLL";

    /** 新闻节点与字段：result.cmsArticleWebOld[] 的 title/content/mediaName/date/url。 */
    static final String NODE_CMS_ARTICLE_WEB_OLD = "cmsArticleWebOld";
    static final String FIELD_TITLE = "title";
    static final String FIELD_CONTENT = "content";
    static final String FIELD_MEDIA_NAME = "mediaName";
    static final String FIELD_DATE = "date";
    static final String FIELD_URL = "url";

    private EastmoneyFormat() {}

    /**
     * 东财实时行情（fltt=2）整体解析：字段码提取（本类）→ 中间结构 → domain 解析。
     * 异常语义与下沉前 {@code MarketDataParser.parseQuote(JsonNode)} 逐字一致
     * （data 缺失/价格无效抛 BAD_RESPONSE）。
     */
    public static Quote parseQuote(JsonNode root) {
        return MarketDataParser.parseQuote(extractQuote(root));
    }

    /**
     * 东财指数速览（push2）整体解析：f12/f14/f2/f4/f3 提取（本类）→ 中间结构 → domain 组装。
     * 空列表抛 BAD_RESPONSE「指数数据为空」的口径由 domain {@code buildOverview} 保持。
     */
    public static MarketOverview buildOverview(JsonNode root) {
        return MarketDataParser.buildOverview(extractOverview(root));
    }

    /** 东财股票搜索整体解析：QuotationCodeTable 提取 + 非 A 股过滤（本类）→ domain 市场名归一化。 */
    public static List<StockHit> parseSearch(JsonNode root) {
        return MarketDataParser.parseSearch(extractSearch(root));
    }

    /** 东财 K线整体解析：data.klines 拆分（本类）→ domain 手→股归一化与空校验。 */
    public static List<KlineBar> parseKline(JsonNode root) {
        return MarketDataParser.parseKline(extractKline(root));
    }

    /** 东财财务指标序列整体解析：F10 字段码提取（本类）→ domain 报告期截断归一化。 */
    public static List<FinancialIndicator> parseFinancials(JsonNode root) {
        return MarketDataParser.parseFinancialIndicators(extractFinancials(root));
    }

    /** 东财新闻整体解析：cmsArticleWebOld 提取（本类）→ domain HTML 剥离与摘要截断。 */
    public static List<NewsItem> parseNews(JsonNode root) {
        return MarketDataParser.parseNews(extractNews(root));
    }

    /**
     * data 字段码 → {@link RawQuote} 规范化中间结构（domain 解析器的唯一入参形态）。
     * data 缺失/null 抛 BAD_RESPONSE「行情数据为空」——与下沉前口径一致。
     */
    public static RawQuote extractQuote(JsonNode root) {
        JsonNode data = root.path(NODE_DATA);
        if (data.isMissingNode() || data.isNull()) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "行情数据为空");
        }
        return new RawQuote(
                data.path(F57_CODE).asText(""),
                data.path(F58_NAME).asText(""),
                data.path(F43_PRICE).asDouble(),
                numOrZero(data.path(F169_CHANGE)),
                numOrZero(data.path(F170_CHANGE_PCT)),
                numOrZero(data.path(F46_OPEN)),
                numOrZero(data.path(F44_HIGH)),
                numOrZero(data.path(F45_LOW)),
                numOrZero(data.path(F60_PREV_CLOSE)),
                data.path(F47_VOLUME).asLong(),
                numOrZero(data.path(F48_AMOUNT)),
                nullable(data.path(F162_PE)),
                nullable(data.path(F167_PB)),
                data.path(F86_TIMESTAMP).asLong());
    }

    /** data.diff[] 的 f12/f14/f2/f4/f3 → {@link RawIndexQuote} 列表（diff 非数组返回空，与下沉前一致）。 */
    public static List<RawIndexQuote> extractOverview(JsonNode root) {
        JsonNode diff = root.path(NODE_DATA).path(NODE_DIFF);
        List<RawIndexQuote> list = new ArrayList<>();
        if (diff.isArray()) {
            for (JsonNode item : diff) {
                list.add(new RawIndexQuote(
                        item.path(F12_CODE).asText(""),
                        item.path(F14_NAME).asText(""),
                        numOrZero(item.path(F2_PRICE)),
                        numOrZero(item.path(F4_CHANGE)),
                        numOrZero(item.path(F3_CHANGE_PCT))));
            }
        }
        return list;
    }

    /**
     * QuotationCodeTable.Data[] → {@link RawStockHit} 列表：SecurityTypeName 命中
     * 指数/板块/基金/债/期货的条目过滤不返（非 A 股过滤决策为东财 wire 知识，随字段码
     * 一并下沉本类——2026-10-15 L2）；Data 非数组返回空，与下沉前一致。
     */
    public static List<RawStockHit> extractSearch(JsonNode root) {
        JsonNode data = root.path(NODE_QUOTATION_CODE_TABLE).path(NODE_DATA_ITEMS);
        List<RawStockHit> hits = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                String typeName = item.path(FIELD_SECURITY_TYPE_NAME).asText("");
                if (typeName.contains("指数") || typeName.contains("板块") || typeName.contains("基金")
                        || typeName.contains("债") || typeName.contains("期货")) {
                    continue;
                }
                hits.add(new RawStockHit(
                        item.path(FIELD_CODE).asText(""),
                        item.path(FIELD_NAME).asText(""),
                        item.path(FIELD_MKT_NUM).asText("1"),
                        typeName));
            }
        }
        return hits;
    }

    /**
     * data.klines CSV 行数组 → {@link RawKlineBar} 列表：klines 缺失/null 抛 BAD_RESPONSE
     * 「K线数据为空」（与下沉前口径一致）；不足 8 列的行跳过（跳过逻辑为 wire 行形态知识，
     * 随本类下沉）；全部行被跳过时返回空列表，空列表抛异常的口径留在 domain parseKline。
     */
    public static List<RawKlineBar> extractKline(JsonNode root) {
        JsonNode klines = root.path(NODE_DATA).path(NODE_KLINES);
        if (klines.isMissingNode() || klines.isNull()) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "K线数据为空");
        }
        List<RawKlineBar> bars = new ArrayList<>();
        for (JsonNode line : klines) {
            String[] f = line.asText().split(",");
            if (f.length < 8) {
                continue;
            }
            bars.add(new RawKlineBar(
                    f[0],
                    numOrZero(f[1]),
                    numOrZero(f[2]),
                    numOrZero(f[3]),
                    numOrZero(f[4]),
                    parseLongOrZero(f[5]),
                    numOrZero(f[6]),
                    numOrZero(f[7])));
        }
        return bars;
    }

    /**
     * result.data[] 的 F10 字段码 → {@link RawFinancialIndicator} 列表：data 缺失/null/
     * 非数组抛 BAD_RESPONSE「财务数据为空」（与下沉前口径一致，触发点在本方法）；
     * 可空字段缺失/null/非数值归 null（与下沉前 nullable(JsonNode) 同口径）。
     */
    public static List<RawFinancialIndicator> extractFinancials(JsonNode root) {
        JsonNode data = root.path(NODE_RESULT).path(NODE_DATA);
        if (data.isMissingNode() || data.isNull() || !data.isArray()) {
            throw new MarketDataException(MarketDataErrorCode.BAD_RESPONSE, "财务数据为空");
        }
        List<RawFinancialIndicator> list = new ArrayList<>();
        for (JsonNode item : data) {
            list.add(new RawFinancialIndicator(
                    item.path(FIELD_REPORT_DATE).asText(""),
                    nullable(item.path(FIELD_EPSJB)),
                    nullable(item.path(FIELD_BPS)),
                    nullable(item.path(FIELD_TOTALOPERATEREVE)),
                    nullable(item.path(FIELD_PARENTNETPROFIT)),
                    nullable(item.path(FIELD_ROEJQ)),
                    nullable(item.path(FIELD_XSMLL))));
        }
        return list;
    }

    /** result.cmsArticleWebOld[] → {@link RawNewsItem} 列表（原文透传：HTML 剥离/摘要截断归 domain；
     *  节点非数组返回空，与下沉前一致）。 */
    public static List<RawNewsItem> extractNews(JsonNode root) {
        JsonNode data = root.path(NODE_RESULT).path(NODE_CMS_ARTICLE_WEB_OLD);
        List<RawNewsItem> list = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                list.add(new RawNewsItem(
                        item.path(FIELD_TITLE).asText(""),
                        item.path(FIELD_CONTENT).asText(""),
                        item.path(FIELD_MEDIA_NAME).asText(""),
                        item.path(FIELD_DATE).asText(""),
                        item.path(FIELD_URL).asText("")));
            }
        }
        return list;
    }

    /** 数值字段：缺失/非数值（"-"）归 0（与下沉前 numOrZero(JsonNode) 同口径）。 */
    private static double numOrZero(JsonNode n) {
        return n.asDouble();
    }

    /** 数值字段（CSV 文本形态）：缺失/非数值归 0（与下沉前 numOrZero(String) 同口径）。 */
    private static double numOrZero(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 整数字段（CSV 文本形态）：缺失/非数值归 0（与下沉前 parseLongOrZero 同口径）。 */
    private static long parseLongOrZero(String s) {
        try {
            return (long) Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 可空数值字段：缺失/null/非数值（"-"）归 null（与下沉前 nullable(JsonNode) 同口径）。 */
    private static Double nullable(JsonNode n) {
        if (n == null || n.isNull() || !n.isNumber()) {
            return null;
        }
        return n.asDouble();
    }
}

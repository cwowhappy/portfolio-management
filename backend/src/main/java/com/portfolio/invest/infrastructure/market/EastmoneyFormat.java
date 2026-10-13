package com.portfolio.invest.infrastructure.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.portfolio.invest.domain.market.MarketDataErrorCode;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.MarketDataParser;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.market.RawQuote;

/**
 * 东财 wire format 知识单点（2026-10-12 审查 §三.7 字段码下沉）：fltt=2 实时行情响应的
 * 字段码（f43/f57/f58...）只存在本类——data 字段码 → {@link RawQuote} 已规范化中间结构的
 * 映射在此完成，domain {@link MarketDataParser} 只解析中间结构（domain 零供应商知识、
 * 零 infrastructure 依赖，ArchUnit 白名单不变）。主源切换时只需替换本映射层。
 *
 * <p>数值口径（与下沉前 domain 解析逐字一致）：fltt=2 下数值均为浮点，异常值为 "-" 字符串
 * （数值字段 asDouble 归 0，PE/PB nullable 归 null）；f47 成交量单位不稳定，归一化仍在
 * domain {@code normalizeVolume} 承担。
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
     * data 字段码 → {@link RawQuote} 规范化中间结构（domain 解析器的唯一入参形态）。
     * data 缺失/null 抛 BAD_RESPONSE「行情数据为空」——与下沉前口径一致。
     */
    public static RawQuote extractQuote(JsonNode root) {
        JsonNode data = root.path("data");
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

    /** 数值字段：缺失/非数值（"-"）归 0（与下沉前 numOrZero(JsonNode) 同口径）。 */
    private static double numOrZero(JsonNode n) {
        return n.asDouble();
    }

    /** 正数可空字段（PE/PB）：缺失/null/非数值（"-"）归 null（与下沉前 nullable(JsonNode) 同口径）。 */
    private static Double nullable(JsonNode n) {
        if (n == null || n.isNull() || !n.isNumber()) {
            return null;
        }
        return n.asDouble();
    }
}

package com.portfolio.invest.infrastructure.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.portfolio.invest.domain.market.IndexQuote;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketDataErrorCode;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.Quote;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 腾讯 wire format 知识单点（2026-10-16 审查 L2-二期收口）：qt.gtimg.cn 的 {@code ~} 分隔
 * 文本行情行（{@code v_sh600519="1~名称~代码~价~昨收~今开~量(手)~...~时间~涨跌~涨跌幅~高~低~
 * ...~量(手)~额(万)~换手~PE(TTM)~...~PB~..."}，47+ 字段）与 ifzq.gtimg.cn 的
 * {@code data.{symbol}.{qfq+period}} K线数组的字段序/路径导航/单位换算（手→股、万元→元、
 * {@code yyyyMMddHHmmss} 时间戳→{@code "yyyy-MM-dd HH:mm"}）只存在本类——「腾讯响应长什么样」的
 * 供应商知识随供应商走，domain {@code MarketDataParser} 不再携带。
 *
 * <p>腾讯帧不复用东财 {@code Raw*} 中间结构：时间戳为 14 位文本、成交额以万元进位、K线仅 6 列
 * （无成交额/振幅，按日期升序）、空数据文案为「腾讯*数据为空」——供应商特性已在本类完整承载，
 * 直接构造 domain DTO；异常文案/触发口径与下沉前 domain 解析逐字一致（零行为变更）。
 * 主源切换时只需替换本映射层。
 */
public final class TencentFormat {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");

    private TencentFormat() {}

    /** 腾讯实时行情（主源）解析（原 domain {@code MarketDataParser.parseTencentQuote}，
     *  2026-10-16 原样下沉）。 */
    public static Quote parseQuote(String raw, String code) {
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

    /** 腾讯指数速览（主源）：多行 v_s_sh000001="1~上证指数~000001~点位~涨跌~涨跌幅~...";
     *  （原 {@code MarketDataParser.buildTencentOverview}，2026-10-16 原样下沉）。 */
    public static MarketOverview buildOverview(String raw) {
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
     * 腾讯 K线兜底：data.{symbol}.{qfq+period} 为 [date, open, close, high, low, volume(手)] 数组，
     * 成交量统一转股，按日期升序。腾讯仅返回 6 列（无成交额/振幅），amount 与 amplitudePct 置 0。
     * （原 {@code MarketDataParser.parseTencentKline}，2026-10-16 原样下沉。）
     */
    public static List<KlineBar> parseKline(JsonNode root, String symbol, String period) {
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
}

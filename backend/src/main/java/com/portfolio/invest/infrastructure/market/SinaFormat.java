package com.portfolio.invest.infrastructure.market;

import com.portfolio.invest.domain.market.IndexQuote;
import com.portfolio.invest.domain.market.MarketDataErrorCode;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.Quote;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 新浪 wire format 知识单点（2026-10-16 审查 L2-二期收口）：GBK 文本行协议
 * （{@code var hq_str_sh600519="名称,今开,昨收,最新价,最高,最低,..."} 行情行、
 * {@code var hq_str_s_sh000001="名称,点位,涨跌,涨跌幅,..."} 指数行）的引号提取、逗号分隔、
 * 字段序、涨跌额/涨跌幅由昨收回算（{@code round2}）与变量名指数代码提取只存在本类——
 * 「新浪响应长什么样」的供应商知识随供应商走，domain {@code MarketDataParser} 不再携带。
 *
 * <p>新浪帧不复用东财 {@code Raw*} 中间结构：时间为 {@code "yyyy-MM-dd HH:mm:ss"} 文本而非
 * epoch 秒、成交量不经过 {@code normalizeVolume} 归一化、PE/PB 源不给出——供应商特性已在
 * 本类完整承载，直接构造 domain DTO；异常文案/触发口径与下沉前 domain 解析逐字一致
 * （零行为变更）。主源切换时只需替换本映射层。
 */
public final class SinaFormat {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");

    private SinaFormat() {}

    /** 新浪行情兜底解析（原 domain {@code MarketDataParser.parseSinaQuote}，2026-10-16 原样下沉）。 */
    public static Quote parseQuote(String raw, String code) {
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

    /** 新浪指数兜底：多行 var hq_str_s_sh000001="上证指数,3990.30,7.65,0.19,...";（原
     *  {@code MarketDataParser.buildSinaOverview}，2026-10-16 原样下沉）。 */
    public static MarketOverview buildOverview(String raw) {
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

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

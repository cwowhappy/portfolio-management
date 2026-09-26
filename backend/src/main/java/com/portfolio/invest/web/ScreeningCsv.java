package com.portfolio.invest.web;

import com.portfolio.invest.domain.screening.FundScreeningResult;
import com.portfolio.invest.domain.screening.StockScreeningResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 筛选结果 CSV 序列化（web 层表现职责）：UTF-8 + BOM、CRLF 行尾、RFC 4180 转义、总市值换算亿元；
 * 基金 CSV 表头后带 TE 收盘价口径注行（issue #56）。 */
final class ScreeningCsv {

    private static final String HEADER = "代码,名称,PE-TTM,PB,股息率,ROE,ROA,毛利率,资产负债率,流动比率,营收增速,净利增速,总市值(亿),换手率";
    private static final String FUND_HEADER = "代码,名称,费率(%),规模(亿元),跟踪指数,类别,跟踪误差(%,收盘价口径)";
    /** TE 口径注（issue #56）：收盘价自算含分红/折溢价噪声；刻意无 ASCII 逗号，保单单元格免转义。 */
    private static final String FUND_TE_NOTE = "# 注：跟踪误差为收盘价口径（含分红/折溢价噪声）与官方净值口径不可直接对比";
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final BigDecimal YI = new BigDecimal("100000000"); // 亿元换算
    private static final BigDecimal HUNDRED = new BigDecimal("100"); // TE 小数→百分数

    private ScreeningCsv() {}

    static byte[] toCsv(List<StockScreeningResult> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append("\r\n");
        for (var r : rows) {
            sb.append(escape(r.stockCode())).append(',')
                    .append(escape(r.stockName())).append(',')
                    .append(num(r.peTtm())).append(',')
                    .append(num(r.pb())).append(',')
                    .append(num(r.dividendYield())).append(',')
                    .append(num(r.roe())).append(',')
                    .append(num(r.roa())).append(',')
                    .append(num(r.grossMargin())).append(',')
                    .append(num(r.debtToAssets())).append(',')
                    .append(num(r.currentRatio())).append(',')
                    .append(num(r.revenueYoy())).append(',')
                    .append(num(r.netprofitYoy())).append(',')
                    .append(r.totalMv() == null ? "" : r.totalMv().divide(YI, 2, RoundingMode.HALF_UP).toPlainString()).append(',')
                    .append(num(r.turnoverRate())).append("\r\n");
        }
        return withBom(sb);
    }

    /** ETF 筛选导出：fee_rate/scale 原值（%/亿元），tracking_error_1y 小数×100 两位小数；null 输出空串。 */
    static byte[] toFundCsv(List<FundScreeningResult> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(FUND_HEADER).append("\r\n");
        sb.append(FUND_TE_NOTE).append("\r\n");
        for (var r : rows) {
            sb.append(escape(r.fundCode())).append(',')
                    .append(escape(r.fundName())).append(',')
                    .append(num(r.feeRate())).append(',')
                    .append(num(r.scale())).append(',')
                    .append(escape(r.trackingIndexName())).append(',')
                    .append(escape(r.category())).append(',')
                    .append(r.trackingError1y() == null ? ""
                            : r.trackingError1y().multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP).toPlainString())
                    .append("\r\n");
        }
        return withBom(sb);
    }

    private static byte[] withBom(StringBuilder sb) {
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, out, 0, BOM.length);
        System.arraycopy(body, 0, out, BOM.length, body.length);
        return out;
    }

    private static String num(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    /** RFC 4180：含逗号/引号/换行时整体加引号并双写内部引号。 */
    private static String escape(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}

package com.portfolio.invest.web;

import com.portfolio.invest.domain.screening.StockScreeningResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 筛选结果 CSV 序列化（web 层表现职责）：UTF-8 + BOM、CRLF 行尾、RFC 4180 转义、总市值换算亿元。 */
final class ScreeningCsv {

    private static final String HEADER = "代码,名称,PE-TTM,PB,股息率,ROE,ROA,毛利率,资产负债率,流动比率,营收增速,净利增速,总市值(亿),换手率";
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final BigDecimal YI = new BigDecimal("100000000"); // 亿元换算

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

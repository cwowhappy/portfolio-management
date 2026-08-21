package com.portfolio.invest.domain.market;

/** 股票代码规范化结果：code=6位代码, market=东财市场号(1沪/0深), secid, sina前缀, secuCode。 */
public record StockRef(String code, String market, String secid, String sinaPrefix, String secuCode) {

    public static StockRef from(String input) {
        String s = input == null ? "" : input.trim().toLowerCase().replace(" ", "");
        if (s.endsWith(".sh") || s.endsWith(".sz") || s.endsWith(".bj")) {
            s = s.substring(0, s.length() - 3);
        }
        if (s.startsWith("sh") || s.startsWith("sz") || s.startsWith("bj")) {
            s = s.substring(2);
        }
        if (!s.matches("\\d{6}")) {
            throw new MarketDataException("INVALID_CODE", "无效的股票代码: " + input);
        }
        String market;
        String secuSuffix;
        String sinaPrefix;
        if (s.startsWith("6") || s.startsWith("9")) {
            market = "1";
            secuSuffix = "SH";
            sinaPrefix = "sh";
        } else if (s.startsWith("4") || s.startsWith("8")) {
            market = "0";
            secuSuffix = "BJ";
            sinaPrefix = "bj";
        } else {
            market = "0";
            secuSuffix = "SZ";
            sinaPrefix = "sz";
        }
        return new StockRef(s, market, market + "." + s, sinaPrefix, s + "." + secuSuffix);
    }
}

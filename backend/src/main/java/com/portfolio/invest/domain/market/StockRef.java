package com.portfolio.invest.domain.market;

/**
 * 股票代码规范化结果：code=6位代码, market=东财市场号(1沪/0深), secid, sina前缀, secuCode。
 *
 * <p>沪深/北交所市场判定收敛到 {@link Exchange#of(String, String)} 一处（B-25），
 * StockRef 与 MarketDataParser 均复用它，避免「代码前缀 vs MktNum」两套规则漂移。
 */
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
            throw new MarketDataException(MarketDataErrorCode.INVALID_CODE, "无效的股票代码: " + input);
        }
        Exchange ex = Exchange.of(s, null);
        return new StockRef(s, ex.marketNum, ex.marketNum + "." + s, ex.sinaPrefix, s + "." + ex.secuSuffix);
    }

    /** 沪深/北交所统一判定：以代码前缀为准，东财 MktNum 兜底（指数/特殊标的）。 */
    public enum Exchange {
        SH("沪市", "1", "sh", "SH"),
        SZ("深市", "0", "sz", "SZ"),
        BJ("北交所", "0", "bj", "BJ");

        private final String displayName;
        private final String marketNum;
        private final String sinaPrefix;
        private final String secuSuffix;

        Exchange(String displayName, String marketNum, String sinaPrefix, String secuSuffix) {
            this.displayName = displayName;
            this.marketNum = marketNum;
            this.sinaPrefix = sinaPrefix;
            this.secuSuffix = secuSuffix;
        }

        public String displayName() {
            return displayName;
        }

        public String marketNum() {
            return marketNum;
        }

        public String sinaPrefix() {
            return sinaPrefix;
        }

        public String secuSuffix() {
            return secuSuffix;
        }

        public static Exchange of(String code, String mktNum) {
            if (code.startsWith("6") || code.startsWith("9")) {
                return SH;
            }
            if (code.startsWith("4") || code.startsWith("8")) {
                return BJ;
            }
            if ("1".equals(mktNum)) {
                return SH;
            }
            return SZ;
        }
    }
}

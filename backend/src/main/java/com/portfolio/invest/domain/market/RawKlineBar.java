package com.portfolio.invest.domain.market;

/**
 * 已规范化的单根 K 线中间结构（供应商无关）：由基础设施层把上游 wire format
 * （如东财 data.klines 的 CSV 行）拆分映射为本结构后交给 {@link MarketDataParser}——
 * 「哪家字段在什么路径、什么分隔」的供应商知识留在 infrastructure（2026-10-15 审查
 * §五 L2 字段码下沉配套引入，同 {@link RawQuote} 先例）。
 *
 * @param date      交易日（yyyy-MM-dd，原始文本）
 * @param open      开盘价
 * @param close     收盘价
 * @param high      最高价
 * @param low       最低价
 * @param volume    成交量（原始单位——东财为手，手→股归一化由 domain 解析器承担，
 *                  与 {@code TencentFormat} 腾讯 K线解析同口径）
 * @param amount    成交额
 * @param amplitude 振幅 %
 */
public record RawKlineBar(
        String date,
        double open,
        double close,
        double high,
        double low,
        long volume,
        double amount,
        double amplitude) {
}

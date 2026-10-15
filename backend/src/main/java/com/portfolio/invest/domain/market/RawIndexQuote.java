package com.portfolio.invest.domain.market;

/**
 * 已规范化的指数行情中间结构（供应商无关）：由基础设施层把上游 wire format
 * （如东财 push2 接口 f12/f14/f2/f4/f3 字段码）映射为本结构后交给
 * {@link MarketDataParser}——「哪家字段码是什么」的供应商知识留在 infrastructure，
 * domain 只认语义化字段（2026-10-15 审查 §五 L2 字段码下沉配套引入，同 {@link RawQuote} 先例）。
 *
 * @param code      指数代码
 * @param name      指数名称
 * @param price     最新点位
 * @param change    涨跌额
 * @param changePct 涨跌幅 %
 */
public record RawIndexQuote(
        String code,
        String name,
        double price,
        double change,
        double changePct) {
}

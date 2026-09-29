package com.portfolio.invest.domain.research;

import java.math.BigDecimal;

/**
 * 行情/估值快照（D10）：{@link FalsifierEvaluator} 的唯一取值来源，
 * 由 application 组装侧从行情收盘价 + stock_valuation_daily 估值注入（domain 不依赖 market）。
 * 字段 null 表示该口径最近值不可得（对应谓词跳过不自动命中）；
 * priceNote 携带口径说明（如「东财收盘 2026-09-26」），供命中 basis 可解释文案引用。
 */
public record MarketSnapshot(BigDecimal close, BigDecimal pe, BigDecimal pb, String priceNote) {
}

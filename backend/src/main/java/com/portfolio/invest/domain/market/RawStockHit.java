package com.portfolio.invest.domain.market;

/**
 * 已规范化的股票搜索命中中间结构（供应商无关）：由基础设施层把上游 wire format
 * （如东财 QuotationCodeTable 的 Code/MktNum/SecurityTypeName 字段）映射为本结构后交给
 * {@link MarketDataParser}——含「非 A 股条目过滤」在内的 SecurityTypeName 判定（wire 知识）
 * 在 infrastructure 侧完成，本结构只承载过滤后的 A 股候选（2026-10-15 审查 §五 L2 下沉引入）。
 *
 * @param code     标的代码
 * @param name     标的名称
 * @param mktNum   市场号（交易所归属判定经 domain {@code StockRef.Exchange} 完成）
 * @param typeName 上游证券类型名（原始透传，domain 解析不消费——过滤决策已下沉 infrastructure）
 */
public record RawStockHit(
        String code,
        String name,
        String mktNum,
        String typeName) {
}

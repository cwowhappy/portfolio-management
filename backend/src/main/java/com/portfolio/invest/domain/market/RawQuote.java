package com.portfolio.invest.domain.market;

/**
 * 已规范化的行情报价中间结构（供应商无关）：由基础设施层把上游 wire format
 * （如东财 fltt=2 字段码）映射为本结构后交给 {@link MarketDataParser} 解析——
 * 「哪家字段码是什么」的供应商知识留在 infrastructure，domain 只认语义化字段
 * （2026-10-12 审查 §三.7 字段码下沉配套引入）。
 *
 * @param code          标的代码
 * @param name          标的名称
 * @param price         最新价（无效值映射为 0，由解析器校验拒绝）
 * @param change        涨跌额
 * @param changePct     涨跌幅 %
 * @param open          今开
 * @param high          最高
 * @param low           最低
 * @param prevClose     昨收
 * @param volume        成交量（原始单位，手/股归一化由解析器承担）
 * @param amount        成交额
 * @param pe            市盈率（可空——源未给出为 null，不得编造）
 * @param pb            市净率（可空）
 * @param epochSeconds  行情时间戳（epoch 秒；0/缺失 = 无时间戳）
 */
public record RawQuote(
        String code,
        String name,
        double price,
        double change,
        double changePct,
        double open,
        double high,
        double low,
        double prevClose,
        long volume,
        double amount,
        Double pe,
        Double pb,
        long epochSeconds) {
}

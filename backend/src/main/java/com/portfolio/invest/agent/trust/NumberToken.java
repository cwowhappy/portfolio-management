package com.portfolio.invest.agent.trust;

import java.math.BigDecimal;

/**
 * 文本中提取的数字 token（参数组②判定式产物，{@link NumberExtractor} 输出，MS-29 B1）。
 *
 * <p>snippet+occ 定位而非字符偏移——原位替换后偏移漂移，片段+序次稳定（设计规格 §2.1）；
 * occ 对同 snippet 的<strong>全部</strong>出现计数（含非数据性出现），与前端 remark 插件
 * 按原文计数保持一致。契约（设计规格 §4.2）：豁免与统计只消费 {@code dataLike} 为 true 的 token。
 */
public record NumberToken(
        String snippet,
        BigDecimal value,
        int occ,
        boolean dataLike,
        boolean percent) {}

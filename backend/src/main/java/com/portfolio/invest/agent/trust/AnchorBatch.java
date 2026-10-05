package com.portfolio.invest.agent.trust;

import java.util.List;

/**
 * 一条 assistant 消息的锚定集 + stats + 修正注记（设计规格 §三）。
 * correctionNotes 仅在大偏差改写发生时非空（注记行「&gt; ⚠ 校验修正：原文误述 X」，决策 #10）；
 * stats 为 F03 未溯源比例信号的数据源（口径排除用户来源复述数字，决策 #16）。
 */
public record AnchorBatch(
        List<AnchorRecord> anchors,
        Stats stats,
        List<String> correctionNotes) {

    /** 三态计数（payload v1 stats 字段）。 */
    public record Stats(int verified, int sourced, int unverified) {}
}

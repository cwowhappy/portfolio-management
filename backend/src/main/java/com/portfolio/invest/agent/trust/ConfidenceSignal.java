package com.portfolio.invest.agent.trust;

/**
 * 低置信四类机制信号（决策 #3：机制信号推导，非模型自评）+ 命中明细
 * （阈值参数组⑤，ConfidenceScorer 消费，B7）。
 * 四类：未溯源数字比例 / 数据时间戳陈旧度 / 工具调用失败降级 / 校验修正次数（含修正失败，决策 #12）。
 */
public enum ConfidenceSignal {
    UNVERIFIED_RATIO("unverified_ratio"),
    STALE_QUOTES("stale_quotes"),
    TOOL_FAILURES("tool_failures"),
    CORRECTIONS("corrections");

    private final String wireName;

    ConfidenceSignal(String wireName) {
        this.wireName = wireName;
    }

    /** payload v1 confidence.signals 条目名（设计规格 §2.1 示例 "stale_quotes:3"）。 */
    public String wireName() {
        return wireName;
    }

    /** 命中明细：metric 为比例（unverified_ratio，如 0.4）或计数（其余三类，如 3）。 */
    public record Hit(ConfidenceSignal signal, double metric) {}
}

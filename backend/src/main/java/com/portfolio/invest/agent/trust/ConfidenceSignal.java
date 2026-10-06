package com.portfolio.invest.agent.trust;

import java.util.Locale;

/**
 * 低置信四类机制信号（决策 #3：机制信号推导，非模型自评）+ 命中明细
 * （阈值参数组⑤，{@link ConfidenceScorer} 消费，MS-29 B7）。
 * 四类：未溯源数字比例 / 数据时间戳陈旧度（行情/财报/宏观三族线名）/ 工具调用失败降级
 * / 校验修正次数（修正失败单列，决策 #12）。
 */
public enum ConfidenceSignal {
    UNVERIFIED_RATIO("unverified_ratio"),
    STALE_QUOTES("stale_quotes"),
    STALE_FINANCIALS("stale_financials"),
    STALE_MACRO("stale_macro"),
    TOOL_FAILURES("tool_failures"),
    CORRECTIONS("corrections"),
    CORRECTION_FAILED("correction_failed");

    private final String wireName;

    ConfidenceSignal(String wireName) {
        this.wireName = wireName;
    }

    /** payload v1 confidence.signals 条目名（设计规格 §2.1 示例 "stale_quotes:3"）。 */
    public String wireName() {
        return wireName;
    }

    /**
     * 命中明细：metric 为比例（unverified_ratio，如 0.4）或计数（其余，如 3）。
     * wire 形态 {@code 线名:值}——比例两位小数去尾零（0.75/0.4），计数整数（展示舍入，
     * 阈值判定在 {@link ConfidenceScorer} 用原始 double，不受此影响）。
     */
    public record Hit(ConfidenceSignal signal, double metric) {

        /** payload v1 confidence.signals 条目串（如 {@code unverified_ratio:0.4}）。 */
        public String wire() {
            if (signal == ConfidenceSignal.UNVERIFIED_RATIO) {
                String two = String.format(Locale.ROOT, "%.2f", metric);
                // 去一位尾零：0.40 → 0.4（保留一位小数，与设计规格示例形态一致）
                return signal.wireName() + ":"
                        + (two.endsWith("0") ? two.substring(0, two.length() - 1) : two);
            }
            return signal.wireName() + ":" + (long) metric;
        }
    }
}

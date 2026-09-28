package com.portfolio.invest.domain.research;

/**
 * 纪律检查类型（M16-F10 买入 / F12 加减仓卖出）：与 DB research_check_record.check_type
 * 取值逐字一致。不同类型共用同一检查机制，仅检查项集不同（SELL/REDUCE 追加证伪核对条目）。
 */
public enum CheckType {
    BUY("买入"),
    ADD("加仓"),
    REDUCE("减仓"),
    SELL("卖出");

    private final String label;

    CheckType(String label) { this.label = label; }

    public String label() { return label; }

    /** F12：减仓/卖出前须逐条核对证伪条件（买入/加仓不注入，由 {@link DisciplineCheckService} 消费）。 */
    public boolean requiresFalsifierCheck() {
        return this == SELL || this == REDUCE;
    }
}

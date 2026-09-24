package com.portfolio.invest.domain.allocation;

public final class AllocationErrorCode {
    private AllocationErrorCode() {}

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String INVALID_WEIGHTS = "INVALID_WEIGHTS";
    public static final String INVALID_INPUT = "INVALID_INPUT";
    public static final String INVALID_ANSWERS = "INVALID_ANSWERS";
    /** 回测无可用权重来源（未指定 planId/template 且无激活方案）——与回测编排共用。 */
    public static final String NO_ACTIVE_PLAN = "NO_ACTIVE_PLAN";
    /** REITs 无回测数据源——与 BacktestEngine.REITS_BACKTEST_UNSUPPORTED 同值。 */
    public static final String REITS_BACKTEST_UNSUPPORTED = "REITS_BACKTEST_UNSUPPORTED";
}

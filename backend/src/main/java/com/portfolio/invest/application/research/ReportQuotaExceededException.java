package com.portfolio.invest.application.research;

/**
 * 当日研报 token 配额已用尽（MS-32 B10，设计规格 §3.9）：发起前软检查 used &gt;= daily-budget
 * 即抛——{@code remaining} 携带钳 0 后的当日余量（发起闸门拒批时恒为 0），web 层（B13）
 * 映射 402 供发起响应/前端提示条展示。
 */
public class ReportQuotaExceededException extends RuntimeException {

    private final long remaining;

    public ReportQuotaExceededException(String message, long remaining) {
        super(message);
        this.remaining = remaining;
    }

    /** 当日剩余（钳 0 后；发起闸门拒批时恒 0）。 */
    public long getRemaining() {
        return remaining;
    }
}

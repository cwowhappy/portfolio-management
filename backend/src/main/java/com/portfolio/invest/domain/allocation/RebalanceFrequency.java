package com.portfolio.invest.domain.allocation;

/** 再平衡提醒频率；QUARTERLY/SEMIANNUAL 按 90/180 自然日触发时间提醒。 */
public enum RebalanceFrequency {
    OFF(0), QUARTERLY(90), SEMIANNUAL(180);

    private final int days;

    RebalanceFrequency(int days) {
        this.days = days;
    }

    public int days() {
        return days;
    }
}

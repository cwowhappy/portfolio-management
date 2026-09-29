package com.portfolio.invest.domain.research;

/** 阶段完成度三态（D22：三态 + 完成方式角标，不做百分比）。 */
public enum StageStatus {
    NOT_STARTED("未开始"),
    IN_PROGRESS("进行中"),
    COMPLETED("完成");

    private final String label;

    StageStatus(String label) { this.label = label; }

    public String label() { return label; }
}

package com.portfolio.invest.domain.journal;

public enum PeriodType {
    QUARTERLY("季度"),
    ANNUAL("年度");

    private final String label;

    PeriodType(String label) { this.label = label; }

    public String label() { return label; }
}

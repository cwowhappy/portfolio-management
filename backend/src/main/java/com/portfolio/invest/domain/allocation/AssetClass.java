package com.portfolio.invest.domain.allocation;

public enum AssetClass {
    STOCK("股票"),
    BOND("债券"),
    GOLD("黄金"),
    CASH("现金"),
    REITS("REITs");

    private final String label;

    AssetClass(String label) { this.label = label; }

    public String label() { return label; }
}

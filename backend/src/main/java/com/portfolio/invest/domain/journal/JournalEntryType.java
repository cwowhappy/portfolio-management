package com.portfolio.invest.domain.journal;

public enum JournalEntryType {
    BUY_MEMO("买入备忘"),
    SELL_MEMO("卖出备忘"),
    RESEARCH_NOTE("研究笔记"),
    REVIEW("定期复盘");

    private final String label;

    JournalEntryType(String label) { this.label = label; }

    public String label() { return label; }
}

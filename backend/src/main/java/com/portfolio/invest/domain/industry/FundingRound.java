package com.portfolio.invest.domain.industry;

/**
 * 融资轮次枚举（设计规格 §三）：CSV 输入格式为枚举名（下划线大写，如 PRE_A/A_PLUS/STRATEGIC）；
 * label() 为中文展示名；order() 为声明序即轮次序（早→晚），全景卡轮次分布排序用。
 * parse 非法值抛 IllegalArgumentException——供解析器 L2 捕获转行错误，不在域内吞错。
 */
public enum FundingRound {
    SEED("种子轮"),
    ANGEL("天使轮"),
    PRE_A("Pre-A轮"),
    A("A轮"),
    A_PLUS("A+轮"),
    B("B轮"),
    B_PLUS("B+轮"),
    C("C轮"),
    C_PLUS("C+轮"),
    D("D轮"),
    STRATEGIC("战略投资"),
    PRE_IPO("Pre-IPO轮"),
    IPO("IPO"),
    ACQUIRED("被收购"),
    UNKNOWN("未知");

    private final String label;

    FundingRound(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 轮次序 = 声明序（ordinal 同义但显式化，供排序 API 消费）。 */
    public int order() {
        return ordinal();
    }

    public static FundingRound parse(String s) {
        return valueOf(s);
    }
}

package com.portfolio.invest.domain.industry;

/**
 * 产业链环节层级枚举（设计规格 §三）：wire/DB 同形态为枚举名（UPSTREAM 等，照 V20 tier 列）；
 * label() 为中文展示名；声明序 上游→中游→下游 即链组装 stages 排序键。
 * parse 非法值抛 IllegalArgumentException——供应用服务捕获转 INVALID_TIER，不在域内吞错。
 */
public enum ChainTier {
    UPSTREAM("上游"),
    MIDSTREAM("中游"),
    DOWNSTREAM("下游");

    private final String label;

    ChainTier(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ChainTier parse(String s) {
        return valueOf(s);
    }
}

package com.portfolio.invest.agent.trust;

import java.math.BigDecimal;

/**
 * 校验三态（决策 #5）+ 容差判定细节（容差参数组①，ConsistencyValidator 消费，B2）。
 * 三态语义：verified=与内置工具返回值比对通过 / sourced=有工具来源但未比对（MCP、二次计算值）
 * / unverified=无工具数据支撑。
 */
public enum TrustVerdict {
    VERIFIED("verified"),
    SOURCED("sourced"),
    UNVERIFIED("unverified");

    private final String wireName;

    TrustVerdict(String wireName) {
        this.wireName = wireName;
    }

    /** payload v1 anchor.state 序列化名（设计规格 §2.1）。 */
    public String wireName() {
        return wireName;
    }

    /** 容差判定细节：matchedRaw=命中的工具返回原值（verified 必有），deviation=相对偏差。 */
    public record Judgment(TrustVerdict verdict, String matchedRaw, BigDecimal deviation) {}
}

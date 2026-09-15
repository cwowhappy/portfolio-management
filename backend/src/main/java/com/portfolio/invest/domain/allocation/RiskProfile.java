package com.portfolio.invest.domain.allocation;

import java.math.BigDecimal;
import java.util.Map;

/** 五档风险偏好：各绑定一套推荐配置（百分比，和为 100）。切点 8–15/16–22/23–29/30–35/36–40（规格 FR-D1/FR-D2）。 */
public enum RiskProfile {
    CONSERVATIVE("保守", Map.of(
            AssetClass.STOCK, w("20"), AssetClass.BOND, w("60"),
            AssetClass.GOLD, w("10"), AssetClass.CASH, w("10"))),
    STABLE("稳健", Map.of(
            AssetClass.STOCK, w("35"), AssetClass.BOND, w("45"),
            AssetClass.GOLD, w("10"), AssetClass.CASH, w("10"))),
    BALANCED("平衡", Map.of(
            AssetClass.STOCK, w("50"), AssetClass.BOND, w("30"),
            AssetClass.GOLD, w("10"), AssetClass.CASH, w("10"))),
    GROWTH("成长", Map.of(
            AssetClass.STOCK, w("65"), AssetClass.BOND, w("20"),
            AssetClass.GOLD, w("5"), AssetClass.CASH, w("5"), AssetClass.REITS, w("5"))),
    AGGRESSIVE("进取", Map.of(
            AssetClass.STOCK, w("80"), AssetClass.BOND, w("5"),
            AssetClass.GOLD, w("5"), AssetClass.CASH, w("5"), AssetClass.REITS, w("5")));

    static final int MIN_SCORE = 8;
    static final int MAX_SCORE = 40;

    private final String displayName;
    private final Map<AssetClass, BigDecimal> recommendedWeights;

    RiskProfile(String displayName, Map<AssetClass, BigDecimal> recommendedWeights) {
        this.displayName = displayName;
        this.recommendedWeights = Map.copyOf(recommendedWeights);
    }

    public String displayName() { return displayName; }

    public Map<AssetClass, BigDecimal> recommendedWeights() { return recommendedWeights; }

    /** 总分定档：切点无空洞无重叠；越界分值属调用方错误（答卷校验应先拦截）。 */
    public static RiskProfile fromTotalScore(int totalScore) {
        if (totalScore < MIN_SCORE || totalScore > MAX_SCORE) {
            throw new AllocationException(AllocationErrorCode.INVALID_ANSWERS, "总分越界: " + totalScore);
        }
        if (totalScore <= 15) return CONSERVATIVE;
        if (totalScore <= 22) return STABLE;
        if (totalScore <= 29) return BALANCED;
        if (totalScore <= 35) return GROWTH;
        return AGGRESSIVE;
    }

    private static BigDecimal w(String v) { return new BigDecimal(v); }
}

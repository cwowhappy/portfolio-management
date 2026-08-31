package com.portfolio.invest.application.allocation;

import com.portfolio.invest.domain.allocation.AssetClass;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public record WeightView(AssetClass assetClass, BigDecimal weight) {
    /** 按 AssetClass 声明顺序输出非零权重，保证 JSON 顺序稳定。 */
    public static List<WeightView> ordered(Map<AssetClass, BigDecimal> weights) {
        return Arrays.stream(AssetClass.values())
                .filter(weights::containsKey)
                .map(ac -> new WeightView(ac, weights.get(ac)))
                .toList();
    }
}

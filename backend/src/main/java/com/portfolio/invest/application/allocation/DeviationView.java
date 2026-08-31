package com.portfolio.invest.application.allocation;

import com.portfolio.invest.domain.allocation.AssetClass;
import java.math.BigDecimal;
import java.util.List;

public record DeviationView(List<DeviationSlice> slices) {
    public record DeviationSlice(AssetClass assetClass, BigDecimal targetWeight,
                                 BigDecimal actualWeight, BigDecimal deviation) {}
}

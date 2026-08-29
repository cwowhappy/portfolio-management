package com.portfolio.invest.application.portfolio;

import java.math.BigDecimal;
import java.util.List;

public record AssetAllocationView(List<Slice> slices) {
    public record Slice(String category, BigDecimal marketValue, BigDecimal ratio) {}
}

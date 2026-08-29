package com.portfolio.invest.application.portfolio;

import java.math.BigDecimal;
import java.util.List;

public record IndustryDistributionView(List<Slice> slices) {
    public record Slice(String industryName, BigDecimal marketValue, BigDecimal ratio) {}
}

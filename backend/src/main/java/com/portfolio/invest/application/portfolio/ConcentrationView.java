package com.portfolio.invest.application.portfolio;

import java.math.BigDecimal;
import java.util.List;

public record ConcentrationView(List<Holding> holdings, BigDecimal top5Ratio) {
    public record Holding(String stockCode, String stockName, BigDecimal marketValue, BigDecimal ratio) {}
}

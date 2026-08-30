package com.portfolio.invest.application.portfolio;

import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.portfolio.Position;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record PositionView(
        Long id, Long groupId, String stockCode, String stockName,
        BigDecimal quantity, BigDecimal avgCost, BigDecimal price,
        BigDecimal marketValue, BigDecimal floatingPnl, BigDecimal pnlRatio,
        BigDecimal realizedPnl, BigDecimal totalBuyCost, BigDecimal cumulativeCashDividend
) {
    public static PositionView from(Position p, Quote q) {
        BigDecimal price = q == null ? null : BigDecimal.valueOf(q.price());
        BigDecimal marketValue = price == null || p.quantity().signum() == 0
                ? null : price.multiply(p.quantity()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal floatingPnl = marketValue == null ? null : marketValue.subtract(p.costBasis());
        BigDecimal pnlRatio = floatingPnl == null || p.costBasis().signum() == 0 ? null
                : floatingPnl.divide(p.costBasis(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        return new PositionView(p.id(), p.groupId(), p.stockCode(), p.stockName(),
                p.quantity(), p.avgCost(), price, marketValue, floatingPnl, pnlRatio,
                p.realizedPnl(), p.totalBuyCost(), p.cumulativeCashDividend());
    }
}

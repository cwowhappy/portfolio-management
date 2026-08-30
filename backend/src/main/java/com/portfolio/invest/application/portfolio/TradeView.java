package com.portfolio.invest.application.portfolio;

import com.portfolio.invest.domain.portfolio.Trade;
import com.portfolio.invest.domain.portfolio.TradeType;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TradeView(Long id, TradeType type, LocalDate tradeDate,
                        BigDecimal price, BigDecimal quantity, BigDecimal fee) {
    public static TradeView from(Trade t) {
        return new TradeView(t.id(), t.type(), t.tradeDate(), t.price(), t.quantity(), t.fee());
    }
}

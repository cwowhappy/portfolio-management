package com.portfolio.invest.application.portfolio;

import com.portfolio.invest.domain.portfolio.Dividend;
import com.portfolio.invest.domain.portfolio.DividendType;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DividendView(Long id, DividendType type, LocalDate exDate,
                           BigDecimal cashPerShare, BigDecimal stockRatio) {
    public static DividendView from(Dividend d) {
        return new DividendView(d.id(), d.type(), d.exDate(), d.cashPerShare(), d.stockRatio());
    }
}

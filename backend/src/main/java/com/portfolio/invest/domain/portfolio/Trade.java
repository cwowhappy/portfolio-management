package com.portfolio.invest.domain.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record Trade(
        Long id,
        Long positionId,
        TradeType type,
        LocalDate tradeDate,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal fee,
        Instant createdAt
) {}

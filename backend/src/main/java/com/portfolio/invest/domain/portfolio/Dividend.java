package com.portfolio.invest.domain.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record Dividend(
        Long id,
        Long positionId,
        DividendType type,
        LocalDate exDate,
        BigDecimal cashPerShare,
        BigDecimal stockRatio,
        Instant createdAt
) {}

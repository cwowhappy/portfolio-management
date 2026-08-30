package com.portfolio.invest.application.portfolio;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record StockDividendCommand(
        @NotNull Long positionId,
        @NotNull LocalDate exDate,
        @NotNull @DecimalMin("0.0001") BigDecimal stockRatio
) {}

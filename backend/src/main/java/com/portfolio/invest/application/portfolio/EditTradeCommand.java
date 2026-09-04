package com.portfolio.invest.application.portfolio;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record EditTradeCommand(
        @NotNull LocalDate tradeDate,
        @NotNull @DecimalMin("0.0001") BigDecimal price,
        @NotNull @DecimalMin("0.0001") BigDecimal quantity,
        @NotNull @DecimalMin("0") BigDecimal fee,
        @NotNull Long groupId
) {}

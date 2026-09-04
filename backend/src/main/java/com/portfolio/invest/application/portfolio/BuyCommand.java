package com.portfolio.invest.application.portfolio;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record BuyCommand(
        @NotNull Long groupId,
        @NotBlank @Size(max = 16) String stockCode,
        @NotBlank @Size(max = 64) String stockName,
        @NotNull LocalDate tradeDate,
        @NotNull @DecimalMin("0.0001") BigDecimal price,
        @NotNull @DecimalMin("0.0001") BigDecimal quantity,
        @NotNull @DecimalMin("0") BigDecimal fee
) {}

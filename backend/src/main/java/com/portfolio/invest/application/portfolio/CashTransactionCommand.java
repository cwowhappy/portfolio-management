package com.portfolio.invest.application.portfolio;

import com.portfolio.invest.domain.portfolio.CashTransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CashTransactionCommand(
        @NotNull Long groupId,
        @NotNull CashTransactionType type,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotNull LocalDate txDate,
        String note
) {}

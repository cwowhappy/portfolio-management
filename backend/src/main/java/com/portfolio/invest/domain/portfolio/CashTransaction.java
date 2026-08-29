package com.portfolio.invest.domain.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record CashTransaction(
        Long id,
        Long groupId,
        CashTransactionType type,
        BigDecimal amount,
        LocalDate txDate,
        String note,
        Instant createdAt
) {}

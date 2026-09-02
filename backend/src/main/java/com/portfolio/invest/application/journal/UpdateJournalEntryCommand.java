package com.portfolio.invest.application.journal;

import com.portfolio.invest.domain.journal.PeriodType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateJournalEntryCommand(
        String stockCode,
        String stockName,
        Long tradeId,
        @NotBlank String title,
        @NotBlank String content,
        BigDecimal targetPrice,
        BigDecimal stopLoss,
        PeriodType periodType,
        LocalDate periodStart,
        LocalDate periodEnd,
        @NotNull LocalDate eventDate
) {}

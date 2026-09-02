package com.portfolio.invest.application.journal;

import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.journal.PeriodType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record JournalEntryView(
        Long id, JournalEntryType type, String stockCode, String stockName, Long tradeId,
        String title, String content, BigDecimal targetPrice, BigDecimal stopLoss,
        PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
        LocalDate eventDate, Instant createdAt, Instant updatedAt) {

    public static JournalEntryView from(JournalEntry e) {
        return new JournalEntryView(e.id(), e.type(), e.stockCode(), e.stockName(), e.tradeId(),
                e.title(), e.content(), e.targetPrice(), e.stopLoss(),
                e.periodType(), e.periodStart(), e.periodEnd(),
                e.eventDate(), e.createdAt(), e.updatedAt());
    }
}

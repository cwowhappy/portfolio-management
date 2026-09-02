package com.portfolio.invest.application.journal;

import java.time.LocalDate;

public record TimelineEventView(
        TimelineEventType type,
        LocalDate date,
        String title,
        String description,
        String stockCode,
        String stockName,
        Long refId,
        String refType
) {}

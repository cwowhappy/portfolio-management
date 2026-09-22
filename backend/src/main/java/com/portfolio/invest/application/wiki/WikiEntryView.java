package com.portfolio.invest.application.wiki;

import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import java.time.Instant;

public record WikiEntryView(Long id, WikiEntryType type, String title, String content,
                            String category, String industryCode, Instant createdAt, Instant updatedAt) {

    public static WikiEntryView from(WikiEntry e) {
        return new WikiEntryView(e.id(), e.type(), e.title(), e.content(),
                e.category(), e.industryCode(), e.createdAt(), e.updatedAt());
    }
}

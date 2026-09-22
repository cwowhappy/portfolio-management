package com.portfolio.invest.application.wiki;

import com.portfolio.invest.domain.wiki.WikiEntryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateWikiEntryCommand(
        @NotNull WikiEntryType type,
        @NotBlank String title,
        @NotBlank String content,
        String category,
        String industryCode
) {}

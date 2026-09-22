package com.portfolio.invest.application.wiki;

import jakarta.validation.constraints.NotBlank;

public record UpdateWikiEntryCommand(
        @NotBlank String title,
        @NotBlank String content,
        String category,
        String industryCode
) {}

package com.portfolio.invest.application.screening;

import jakarta.validation.constraints.NotBlank;

public record AddWatchlistCommand(@NotBlank String stockCode) {}

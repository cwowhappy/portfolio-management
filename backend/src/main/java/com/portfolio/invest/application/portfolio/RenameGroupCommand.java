package com.portfolio.invest.application.portfolio;

import jakarta.validation.constraints.NotBlank;

public record RenameGroupCommand(@NotBlank String name) {}

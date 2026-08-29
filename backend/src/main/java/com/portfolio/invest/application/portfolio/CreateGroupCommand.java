package com.portfolio.invest.application.portfolio;

import com.portfolio.invest.domain.portfolio.GroupType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateGroupCommand(
        @NotBlank String name,
        @NotNull GroupType type
) {}

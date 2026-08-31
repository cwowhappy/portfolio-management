package com.portfolio.invest.application.allocation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdatePlanCommand(
        @NotBlank String name,
        @NotNull @Size(min = 1) @Valid List<WeightInput> weights
) {}

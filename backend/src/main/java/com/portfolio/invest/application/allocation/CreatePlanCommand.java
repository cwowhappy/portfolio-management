package com.portfolio.invest.application.allocation;

import com.portfolio.invest.domain.allocation.PlanSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreatePlanCommand(
        @NotBlank String name,
        @NotNull PlanSource source,
        @NotNull @Size(min = 1) @Valid List<WeightInput> weights
) {}

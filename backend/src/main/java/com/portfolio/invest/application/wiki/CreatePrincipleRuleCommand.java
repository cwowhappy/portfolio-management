package com.portfolio.invest.application.wiki;

import com.portfolio.invest.domain.wiki.PrincipleMetric;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CreatePrincipleRuleCommand(
        @NotNull PrincipleMetric metric,
        @NotNull BigDecimal threshold,
        boolean enabled,
        String description
) {}

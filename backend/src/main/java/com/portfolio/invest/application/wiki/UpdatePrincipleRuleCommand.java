package com.portfolio.invest.application.wiki;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record UpdatePrincipleRuleCommand(
        @NotNull BigDecimal threshold,
        boolean enabled,
        String description
) {}

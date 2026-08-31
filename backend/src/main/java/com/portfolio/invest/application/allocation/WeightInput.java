package com.portfolio.invest.application.allocation;

import com.portfolio.invest.domain.allocation.AssetClass;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record WeightInput(
        @NotNull AssetClass assetClass,
        @NotNull @DecimalMin("0") BigDecimal weight
) {}

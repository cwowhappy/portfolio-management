package com.portfolio.invest.application.industry;

import jakarta.validation.constraints.NotBlank;

/** 关注行业命令（wire DTO）：申万行业代码，如 801780。 */
public record AddIndustryWatchCommand(@NotBlank String industryCode) {}

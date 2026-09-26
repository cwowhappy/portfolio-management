package com.portfolio.invest.application.industry;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 策展企业单条保存命令（wire DTO，设计规格 §四写侧）：id 不在命令内——POST 传 null、
 * PUT 走路径参数（服务方法 save(id, cmd)）。latestRound 为 FundingRound 枚举名（下划线大写），
 * 枚举合法性在应用服务校验（非法 → INVALID_ROUND）。
 */
public record SaveUnlistedCompanyCommand(@NotBlank String industryCode, @NotBlank String companyName,
                                         String segment, @NotBlank String latestRound,
                                         LocalDate lastFundingDate, BigDecimal totalFundingYi,
                                         String summary, String sourceNote) {}

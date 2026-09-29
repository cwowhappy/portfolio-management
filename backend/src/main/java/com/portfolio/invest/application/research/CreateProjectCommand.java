package com.portfolio.invest.application.research;

import jakarta.validation.constraints.NotBlank;

/** 立项命令（F05）：预填标的代码/名称/行业（筛选器/行业中心带入），withTemplate=true 时带入新分析 SOP 模板。 */
public record CreateProjectCommand(
        @NotBlank String stockCode,
        @NotBlank String stockName,
        String industryCode,
        @NotBlank String title,
        Boolean withTemplate) {}

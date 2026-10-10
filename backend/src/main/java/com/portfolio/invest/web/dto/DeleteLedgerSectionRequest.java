package com.portfolio.invest.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 删日账本节请求（F01）。sectionHeader 为视图中的完整节头行；baseHash 乐观并发校验入参非空。 */
public record DeleteLedgerSectionRequest(
        @NotBlank(message = "sectionHeader 不能为空") String sectionHeader,
        @NotBlank(message = "baseHash 不能为空") String baseHash) {}

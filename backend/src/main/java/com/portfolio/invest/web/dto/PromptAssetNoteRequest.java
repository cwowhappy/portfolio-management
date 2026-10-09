package com.portfolio.invest.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 提示词版本变更说明补注入参（PUT /api/admin/prompt-assets/{id}/note，需求决策 #12）。 */
public record PromptAssetNoteRequest(
        @NotBlank(message = "补注内容不能为空") String note) {}

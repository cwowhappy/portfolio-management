package com.portfolio.invest.web.dto;

import jakarta.validation.constraints.NotNull;

/** baseline 置位入参（PUT /api/admin/eval/runs/{id}/baseline，MS-30 B5）。 */
public record BaselineUpdateRequest(
        @NotNull(message = "baseline 不能为空") Boolean baseline) {}

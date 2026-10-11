package com.portfolio.invest.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 研报确认请求体（MS-32 §3.12 POST /api/research/reports/{id}/confirm）：
 * action ∈ CONFIRM|REJECT（分派 B11 服务 confirm/reject）；REJECT 时 reason 必填
 * 走域校验（REJECT_REASON_REQUIRED → 400，域校验先于状态检查——B2 Minor-4 优先序）。
 */
public record ConfirmReportRequest(
        @NotNull(message = "action 不能为空")
        @Pattern(regexp = "CONFIRM|REJECT", message = "action 仅支持 CONFIRM/REJECT")
        String action,
        String reason) {}

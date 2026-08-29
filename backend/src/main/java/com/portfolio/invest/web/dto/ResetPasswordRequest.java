package com.portfolio.invest.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 管理员重置密码 wire DTO：结构性校验（非空）在此，密码强度规则在 application 层（PasswordPolicy）。 */
public record ResetPasswordRequest(@NotBlank(message = "新密码不能为空") String newPassword) {}

package com.portfolio.invest.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 自助重置密码入参。 */
public record PasswordResetRequest(
        @NotBlank(message = "账号不能为空") String identifier,
        @NotBlank(message = "验证码不能为空") String code,
        @NotBlank(message = "新密码不能为空") String newPassword) {}

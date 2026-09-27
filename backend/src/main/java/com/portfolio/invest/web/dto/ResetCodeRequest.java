package com.portfolio.invest.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 找回密码发码入参：用户名或邮箱。 */
public record ResetCodeRequest(
        @NotBlank(message = "账号不能为空") String identifier) {}

package com.portfolio.invest.application.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 注册入参（H1：入参 Command 即 wire DTO）。结构性校验（非空/长度）在此，密码强度等业务规则在 application 层。 */
public record RegisterCommand(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名最长64个字符")
        String username,
        @NotBlank(message = "密码不能为空")
        String password) {}

package com.portfolio.invest.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 登录 wire DTO：结构性校验（非空）在此，凭据正确性由认证流程判定。 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password,
        Boolean rememberMe) {

    public Boolean rememberMe() {
        return Boolean.TRUE.equals(rememberMe);
    }
}

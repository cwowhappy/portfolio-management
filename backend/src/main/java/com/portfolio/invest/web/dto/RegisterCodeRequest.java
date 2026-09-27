package com.portfolio.invest.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 注册发码入参：发码前预检用户名/密码/邮箱。 */
public record RegisterCodeRequest(
        @NotBlank(message = "用户名不能为空") @Size(max = 64) String username,
        @NotBlank(message = "密码不能为空") String password,
        @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确")
        @Size(max = 254, message = "邮箱最长254个字符") String email) {}

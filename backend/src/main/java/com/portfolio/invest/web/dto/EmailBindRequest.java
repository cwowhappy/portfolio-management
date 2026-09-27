package com.portfolio.invest.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 管理员代填邮箱入参。 */
public record EmailBindRequest(
        @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确")
        @Size(max = 254, message = "邮箱最长254个字符") String email) {}

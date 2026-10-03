package com.portfolio.invest.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** admin 设置 provider token（P1-10）：明文仅在请求体一次经过，服务端加密落库，无回显。 */
public record SetTokenRequest(
        @NotBlank(message = "token 不能为空")
        @Size(max = 512, message = "token 长度超过上限 512")
        String token) {}

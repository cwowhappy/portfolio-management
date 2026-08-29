package com.portfolio.invest.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建会话 wire DTO。结构性校验（非空/长度）在此；
 * 不做 UUID 格式校验——前端 {@code newThreadId()} 可能生成 {@code "t-"+timestamp} 的非 UUID 回退 id。
 */
public record CreateConversationRequest(
        @NotBlank(message = "会话 id 不能为空")
        @Size(max = 64, message = "会话 id 最长64字符") // 与 V2 conversation.id VARCHAR(64) 对齐
        String id) {}

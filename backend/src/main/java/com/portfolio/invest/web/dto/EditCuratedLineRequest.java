package com.portfolio.invest.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 编策展层行请求（F01）。newLine 约束（B2 评审 Minor-2）：非空白且不得含换行——策展层行级
 * 寻址下换行会一行拆两行破坏结构；正则用 \A/\z 绝对锚（$ 会放过尾随换行）。
 */
public record EditCuratedLineRequest(
        @Min(value = 0, message = "lineIndex 不能为负") int lineIndex,
        @NotBlank(message = "expectLine 不能为空") String expectLine,
        @NotBlank(message = "newLine 不能为空白") @Pattern(regexp = "\\A[^\\n\\r]*\\z", message = "newLine 不得包含换行")
        String newLine,
        @NotBlank(message = "baseHash 不能为空") String baseHash) {}

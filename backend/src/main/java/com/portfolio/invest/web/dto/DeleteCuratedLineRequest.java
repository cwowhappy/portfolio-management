package com.portfolio.invest.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** 删策展层行请求（F01）。lineIndex 按视图行序寻址；expectLine/baseHash 乐观并发校验入参非空。 */
public record DeleteCuratedLineRequest(
        @Min(value = 0, message = "lineIndex 不能为负") int lineIndex,
        @NotBlank(message = "expectLine 不能为空") String expectLine,
        @NotBlank(message = "baseHash 不能为空") String baseHash) {}

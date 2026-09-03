package com.portfolio.invest.application.portfolio;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 组合资产配置切片的稳定分类（非 UI 文案契约）。
 * JSON 序列化仍输出中文 label 供前端直接展示，但跨服务判断用枚举而非文案。
 */
public enum AllocationSliceCategory {
    EQUITY("权益"),
    CASH("现金");

    private final String label;

    AllocationSliceCategory(String label) {
        this.label = label;
    }

    @JsonValue
    public String label() {
        return label;
    }
}

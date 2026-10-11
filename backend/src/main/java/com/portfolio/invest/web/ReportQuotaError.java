package com.portfolio.invest.web;

/**
 * 研报配额 402 错误体（MS-32 §3.12）：{@link ApiError} 基础上加 remaining——钳 0 后的
 * 当日余量（发起闸门拒批时恒 0），供前端提示条展示。
 */
public record ReportQuotaError(String code, String message, long remaining) {}

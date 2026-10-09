package com.portfolio.invest.web.dto;

/** 手动触发评测受理响应（POST /api/admin/eval/run 202，MS-30 B5）。 */
public record EvalTriggerResponse(long runId) {}

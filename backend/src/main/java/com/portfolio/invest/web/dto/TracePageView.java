package com.portfolio.invest.web.dto;

import com.portfolio.invest.domain.observability.ObservabilityQueryRepository;
import java.time.Instant;
import java.util.List;

/**
 * 工具调用明细分页视图（GET /api/admin/observability/trace，MS-30 B5 §7.1）：
 * {items, page, total}——args 为 JSONB 原文串（前端预览截断）。
 */
public record TracePageView(List<Item> items, int page, long total) {

    /** 明细行（字段与 tool_invocation_obs 列对齐）。 */
    public record Item(
            long id,
            Long userId,
            String conversationId,
            String messageId,
            String toolName,
            String args,
            String resultText,
            int specCount,
            String asOf,
            String asOfKind,
            boolean mcp,
            boolean failed,
            Long durationMs,
            Instant calledAt) {

        static Item from(ObservabilityQueryRepository.ToolTraceRow row) {
            return new Item(row.id(), row.userId(), row.conversationId(), row.messageId(),
                    row.toolName(), row.argsJson(), row.resultText(), row.specCount(), row.asOf(),
                    row.asOfKind(), row.mcp(), row.failed(), row.durationMs(), row.calledAt());
        }
    }

    /** 领域分页 → 视图（回显请求页号）。 */
    public static TracePageView from(ObservabilityQueryRepository.TracePage page, int pageNumber) {
        return new TracePageView(
                page.rows().stream().map(Item::from).toList(), pageNumber, page.total());
    }
}

package com.portfolio.invest.infrastructure.persistence.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.observability.ObservabilityRecorder;
import com.portfolio.invest.domain.observability.ToolCallObservation;
import com.portfolio.invest.domain.observability.TurnObservation;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 观测写入实现（tool_invocation_obs / turn_observation，V5）。照 PushLog 先例全走 JdbcTemplate
 * 原生 SQL + {@code ?::jsonb}（沿 IntelligenceAnnouncementRepositoryImpl 形态），不建 JPA 门面；
 * 工具行走 batchUpdate（§5.3「1 + N」一次调用批量落）。与读实现
 * {@link ObservabilityQueryRepositoryImpl} 同包两类各司读写。
 *
 * <p><strong>降级（§5.3）：</strong>{@link #recordTurn} 整体 try/catch——观测自身失效（库不可达/
 * 约束冲突/序列化异常）仅 ERROR 日志，绝不向调用方抛（对话主链不因旁路观测阻断）；轮行先落，
 * 工具批失败时轮行保留（best-effort 部分写入）。
 *
 * <p><strong>截断：</strong>resultText 按 {@code invest.eval.observability.result-text-max-bytes}
 * UTF-8 字节安全截断——按码点边界裁剪不切半字符，超限时追加省略标记（含标记总长 ≤ 限额）；
 * 限额放不下标记（过小配置）时退化为无标记边界截断；非正限额视为关闭截断（护栏失效不吞观测原文）。
 */
@Repository
public class ObservabilityRepositoryImpl implements ObservabilityRecorder {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityRepositoryImpl.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 截断省略标记（UTF-8 恒 14B：省略号 3B + ASCII 11B）。 */
    private static final String TRUNCATION_MARKER = "…(truncated)";

    private final JdbcTemplate jdbc;
    private final InvestProperties properties;

    public ObservabilityRepositoryImpl(JdbcTemplate jdbc, InvestProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public void recordTurn(TurnObservation turn, List<ToolCallObservation> tools) {
        try {
            writeTurn(turn);
            writeTools(tools);
        } catch (Exception e) {
            // §5.3 降级：吞异常仅 ERROR 留痕（best-effort 部分写入自然保留），不阻断对话
            log.error("观测落库失败（conversationId={}，toolCount={}）",
                    turn == null ? null : turn.conversationId(), tools == null ? 0 : tools.size(), e);
        }
    }

    private void writeTurn(TurnObservation turn) {
        jdbc.update("""
                INSERT INTO turn_observation (user_id, conversation_id, message_id, prompt_tokens,
                                              completion_tokens, total_tokens, duration_ms,
                                              tool_count, failed, error_summary, trust_stats,
                                              created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                turn.userId(), turn.conversationId(), turn.messageId(),
                turn.promptTokens(), turn.completionTokens(), turn.totalTokens(),
                turn.durationMs(), turn.toolCount(), turn.failed(), turn.errorSummary(),
                toJson(turn.trustStats()), Timestamp.from(turn.createdAt()));
    }

    private void writeTools(List<ToolCallObservation> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        int maxBytes = properties.getEval().getObservability().getResultTextMaxBytes();
        List<Object[]> rows = new ArrayList<>(tools.size());
        for (ToolCallObservation tool : tools) {
            rows.add(new Object[] {
                    tool.userId(), tool.conversationId(), tool.messageId(), tool.toolName(),
                    tool.argsJson(), truncateUtf8(tool.resultText(), maxBytes), tool.specCount(),
                    tool.asOf(), tool.asOfKind(), tool.mcp(), tool.failed(), tool.durationMs(),
                    Timestamp.from(tool.calledAt())});
        }
        jdbc.batchUpdate("""
                INSERT INTO tool_invocation_obs (user_id, conversation_id, message_id, tool_name,
                                                args, result_text, spec_count, as_of, as_of_kind,
                                                mcp, failed, duration_ms, called_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
    }

    // ———— 截断与 JSONB 序列化 ————

    /**
     * UTF-8 字节安全截断：超限时按码点边界裁到预算（限额 − 标记字节）再追加省略标记，
     * 保证落库文本字节长度 ≤ maxBytes；预算非正（限额放不下标记）退化为无标记边界截断。
     * maxBytes ≤ 0 视为关闭截断，原样返回。
     */
    static String truncateUtf8(String text, int maxBytes) {
        if (text == null || maxBytes <= 0 || utf8Length(text) <= maxBytes) {
            return text;
        }
        int budget = maxBytes - utf8Length(TRUNCATION_MARKER);
        if (budget <= 0) {
            return cutAtBoundary(text, maxBytes);
        }
        return cutAtBoundary(text, budget) + TRUNCATION_MARKER;
    }

    /** 逐码点累积 UTF-8 字节，切在第一个越界码点前（不切半字符/代理对）。 */
    private static String cutAtBoundary(String text, int maxBytes) {
        StringBuilder out = new StringBuilder();
        int used = 0;
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            int size = utf8Size(codePoint);
            if (used + size > maxBytes) {
                break;
            }
            out.appendCodePoint(codePoint);
            used += size;
            i += Character.charCount(codePoint);
        }
        return out.toString();
    }

    /** 码点 UTF-8 编码宽度（1..4，标准前缀规则）。 */
    private static int utf8Size(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** JSONB 参数序列化（null 透传 NULL 列；失败抛出由 recordTurn 整体降级兜底）。 */
    private static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("观测 JSONB 参数序列化失败", e);
        }
    }
}

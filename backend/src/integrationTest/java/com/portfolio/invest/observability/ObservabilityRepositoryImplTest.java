package com.portfolio.invest.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.observability.ObservabilityRecorder;
import com.portfolio.invest.domain.observability.ToolCallObservation;
import com.portfolio.invest.domain.observability.TurnObservation;
import com.portfolio.invest.infrastructure.persistence.observability.ObservabilityRepositoryImpl;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 观测写端口真库契约（MS-30 B6，Testcontainers PG16 + V5）：recordTurn 批量 1+N 落库、
 * args/trust_stats JSONB 往返、resultText UTF-8 字节安全截断（默认 2048 + 中文边界 + 退化配置）、
 * 写入失败吞异常仅 ERROR 日志（§5.3 降级——观测自身失效不得阻断对话）。清理（purgeBefore）
 * 归 Task 10，本类不测。
 *
 * <p>@BeforeEach/@AfterEach 全表清空保持共享容器零残留（沿 Task 7 类序教训）。
 * 截断边界用直造实例注入小限额（Spring Bean 仍是默认 2048，不经测试改全局配置）。
 */
@SpringBootTest
class ObservabilityRepositoryImplTest extends PostgresTestSupport {

    @Autowired
    ObservabilityRecorder recorder;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanObservations() {
        jdbc.update("DELETE FROM tool_invocation_obs");
        jdbc.update("DELETE FROM turn_observation");
    }

    // ———— 造数 helpers ————

    private static TurnObservation turn() {
        return new TurnObservation(7L, "conv-obs-1", "msg-obs-1", 1200, 340, 1540, 8_500L, 2,
                false, null, Map.of("verified", 2, "sourced", 1, "corrections", 0),
                Instant.parse("2026-10-06T01:30:00Z"));
    }

    private static ToolCallObservation toolCall(String toolName, String resultText) {
        return new ToolCallObservation(7L, "conv-obs-1", "msg-obs-1", toolName,
                "{\"code\":\"600519\",\"limit\":5}", resultText, 1, "2026-10-06 09:30:00",
                "data", false, false, 320L, Instant.parse("2026-10-06T01:30:05Z"));
    }

    /** 直造实例（注入非默认截断限额）：与 Spring Bean 同构造形态，仅配置不同。 */
    private ObservabilityRecorder recorderWithMaxBytes(int maxBytes) {
        InvestProperties properties = new InvestProperties();
        properties.getEval().getObservability().setResultTextMaxBytes(maxBytes);
        return new ObservabilityRepositoryImpl(jdbc, properties);
    }

    // ———— 1+N 批量落库与 JSONB 往返 ————

    @Test
    @DisplayName("给定一轮两工具，when落库，then两表各落一行且args与trustStats按JSONB往返")
    void givenTurnWithTwoToolCalls_whenRecordTurn_thenBothTablesWrittenWithJsonbRoundTrip() {
        recorder.recordTurn(turn(), List.of(
                toolCall("get_quote", "{\"price\":1520.33}"),
                toolCall("get_kline", "{\"error\":\"timeout\"}")));

        Map<String, Object> turnRow = jdbc.queryForMap(
                "SELECT * FROM turn_observation WHERE conversation_id = 'conv-obs-1'");
        assertThat(turnRow.get("user_id")).isEqualTo(7L);
        assertThat(turnRow.get("message_id")).isEqualTo("msg-obs-1");
        assertThat(turnRow.get("prompt_tokens")).isEqualTo(1200);
        assertThat(turnRow.get("completion_tokens")).isEqualTo(340);
        assertThat(turnRow.get("total_tokens")).isEqualTo(1540);
        assertThat(turnRow.get("duration_ms")).isEqualTo(8_500L);
        assertThat(turnRow.get("tool_count")).isEqualTo(2);
        assertThat(turnRow.get("failed")).isEqualTo(false);
        assertThat(jdbc.queryForObject(
                "SELECT trust_stats->>'verified' FROM turn_observation WHERE conversation_id = 'conv-obs-1'",
                String.class)).isEqualTo("2");
        assertThat(jdbc.queryForObject(
                "SELECT trust_stats->>'sourced' FROM turn_observation WHERE conversation_id = 'conv-obs-1'",
                String.class)).isEqualTo("1");

        Integer toolRows = jdbc.queryForObject(
                "SELECT count(*) FROM tool_invocation_obs WHERE conversation_id = 'conv-obs-1'",
                Integer.class);
        assertThat(toolRows).isEqualTo(2);
        Map<String, Object> quoteRow = jdbc.queryForMap("""
                SELECT * FROM tool_invocation_obs
                 WHERE conversation_id = 'conv-obs-1' AND tool_name = 'get_quote'
                """);
        assertThat(quoteRow.get("args").toString()).contains("600519", "\"limit\"");
        assertThat(jdbc.queryForObject("""
                SELECT args->>'code' FROM tool_invocation_obs
                 WHERE conversation_id = 'conv-obs-1' AND tool_name = 'get_quote'
                """, String.class)).isEqualTo("600519");
        assertThat(quoteRow.get("result_text")).isEqualTo("{\"price\":1520.33}");
        assertThat(quoteRow.get("spec_count")).isEqualTo(1);
        assertThat(quoteRow.get("as_of")).isEqualTo("2026-10-06 09:30:00");
        assertThat(quoteRow.get("as_of_kind")).isEqualTo("data");
        assertThat(quoteRow.get("mcp")).isEqualTo(false);
        assertThat(quoteRow.get("failed")).isEqualTo(false);
        assertThat(quoteRow.get("duration_ms")).isEqualTo(320L);
        assertThat(quoteRow.get("called_at")).isNotNull();
    }

    @Test
    @DisplayName("给定空工具列表，when落库，then仅落轮观测一行不报错")
    void givenNoToolCalls_whenRecordTurn_thenOnlyTurnRowWritten() {
        recorder.recordTurn(turn(), List.of());

        Integer turns = jdbc.queryForObject("SELECT count(*) FROM turn_observation", Integer.class);
        Integer tools = jdbc.queryForObject("SELECT count(*) FROM tool_invocation_obs", Integer.class);
        assertThat(turns).isEqualTo(1);
        assertThat(tools).isZero();
    }

    // ———— resultText 截断（UTF-8 字节安全） ————

    @Test
    @DisplayName("给定10KB结果文本，when默认限额2048落库，then库内字节长度恰2048且带省略标记")
    void givenTenKbResultText_whenRecordTurn_thenStoredAtMost2048BytesWithMarker() {
        String tenKb = "x".repeat(10 * 1024);

        recorder.recordTurn(turn(), List.of(toolCall("get_quote", tenKb)));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT result_text, octet_length(result_text) AS bytes FROM tool_invocation_obs");
        assertThat((Integer) row.get("bytes")).isEqualTo(2048);
        assertThat((String) row.get("result_text"))
                .isEqualTo("x".repeat(2048 - "…(truncated)".getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                        + "…(truncated)");
    }

    @Test
    @DisplayName("给定中文文本在小限额上，when截断，then按码点边界裁剪不切半字符")
    void givenMultiByteTextAtSmallLimit_whenRecordTurn_thenCutAtCodePointBoundary() {
        String chinese = "中".repeat(20); // 60 字节

        // 限额 20：预算 20-14=6B → 两个中文（6B）+ 标记（14B）= 恰 20B
        recorderWithMaxBytes(20).recordTurn(turn(), List.of(toolCall("get_quote", chinese)));
        Map<String, Object> exact = jdbc.queryForMap(
                "SELECT result_text, octet_length(result_text) AS bytes FROM tool_invocation_obs");
        assertThat((Integer) exact.get("bytes")).isEqualTo(20);
        assertThat((String) exact.get("result_text")).isEqualTo("中中…(truncated)");

        // 限额 10（放不下标记 14B）：退化为无标记边界截断 → 三个中文 9B ≤ 10B
        recorderWithMaxBytes(10).recordTurn(turn(), List.of(toolCall("get_quote", chinese)));
        Map<String, Object> fallback = jdbc.queryForMap("""
                SELECT result_text, octet_length(result_text) AS bytes FROM tool_invocation_obs
                 WHERE result_text NOT LIKE '%(truncated)%'
                """);
        assertThat((Integer) fallback.get("bytes")).isEqualTo(9);
        assertThat((String) fallback.get("result_text")).isEqualTo("中中中");
    }

    @Test
    @DisplayName("给定非正限额配置，when落库，then截断关闭原文保留")
    void givenNonPositiveMaxBytes_whenRecordTurn_thenNoTruncation() {
        String text = "y".repeat(4096);

        recorderWithMaxBytes(0).recordTurn(turn(), List.of(toolCall("get_quote", text)));

        Integer bytes = jdbc.queryForObject(
                "SELECT octet_length(result_text) FROM tool_invocation_obs", Integer.class);
        assertThat(bytes).isEqualTo(4096);
    }

    @Test
    @DisplayName("给定限额内的短文本，when落库，then原样写入不加标记")
    void givenShortTextWithinLimit_whenRecordTurn_thenStoredAsIs() {
        recorder.recordTurn(turn(), List.of(toolCall("get_quote", "茅台 1520.33 元")));

        String stored = jdbc.queryForObject(
                "SELECT result_text FROM tool_invocation_obs", String.class);
        assertThat(stored).isEqualTo("茅台 1520.33 元");
    }

    // ———— 降级：写入失败吞异常仅 ERROR 日志（§5.3） ————

    @Test
    @DisplayName("给定工具行约束冲突，when落库，then异常吞掉仅ERROR日志且轮行保留best-effort")
    void givenConstraintViolation_whenRecordTurn_thenExceptionSwallowedWithOnlyErrorLog() {
        Logger implLogger = (Logger) LoggerFactory.getLogger(ObservabilityRepositoryImpl.class);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ListAppender<>();
        appender.start();
        implLogger.addAppender(appender);
        try {
            ToolCallObservation badTool = new ToolCallObservation(7L, "conv-obs-1", "msg-obs-1",
                    null, "{}", "结果", 0, null, null, false, false, 10L,
                    Instant.parse("2026-10-06T01:30:05Z"));

            recorder.recordTurn(turn(), List.of(badTool)); // tool_name NOT NULL → 违反约束
        } finally {
            implLogger.detachAppender(appender);
        }

        // 不向调用方抛（观测失效不得阻断对话）；轮行先落已保留（best-effort 部分写入）
        Integer turns = jdbc.queryForObject(
                "SELECT count(*) FROM turn_observation WHERE conversation_id = 'conv-obs-1'",
                Integer.class);
        Integer tools = jdbc.queryForObject("SELECT count(*) FROM tool_invocation_obs", Integer.class);
        assertThat(turns).isEqualTo(1);
        assertThat(tools).isZero();
        assertThat(appender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage()).contains("conv-obs-1");
                });
    }
}

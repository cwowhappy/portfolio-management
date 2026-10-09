package com.portfolio.invest.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.observability.ObservabilityQueryRepository;
import com.portfolio.invest.domain.observability.ObservabilityQueryRepository.ToolTraceRow;
import com.portfolio.invest.support.PostgresTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 观测两表聚合查询真库契约（MS-30 B5，Testcontainers PG16 + V5）：trace 动态筛选/分页/
 * 总数、cost 按日（Asia/Shanghai 日界）跨两日聚合与按工具均值、latency percentile_cont
 * p50/p95 数值断言（离散有序集线性插值）。观测表写入方在 Task 8/9——本类以 INSERT 直造
 * 数据钉死查询 SQL 契约（表已建、尚无写入方）。
 *
 * <p>@BeforeEach/@AfterEach 全表清空保持共享容器零残留（Task 3 类序教训）。
 */
@SpringBootTest
class ObservabilityQueryRepositoryImplTest extends PostgresTestSupport {

    /** 日界时区：与 SQL 内 AT TIME ZONE 'Asia/Shanghai' 对齐（调度/产品口径一致）。 */
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Autowired
    ObservabilityQueryRepository repository;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanObservations() {
        jdbc.update("DELETE FROM tool_invocation_obs");
        jdbc.update("DELETE FROM turn_observation");
    }

    // ———— 造数 helpers ————

    private void insertToolCall(String tool, boolean failed, Long durationMs, Instant calledAt) {
        jdbc.update("""
                INSERT INTO tool_invocation_obs (user_id, conversation_id, message_id, tool_name,
                                                 args, result_text, spec_count, mcp, failed,
                                                 duration_ms, called_at)
                VALUES (2, 'conv-1', 'msg-1', ?, '{"code":"600519"}'::jsonb, '结果', 1, false, ?, ?, ?)
                """, tool, failed, durationMs, Timestamp.from(calledAt));
    }

    private void insertTurn(int promptTokens, int completionTokens, int totalTokens,
                            Long durationMs, Instant createdAt) {
        jdbc.update("""
                INSERT INTO turn_observation (user_id, conversation_id, message_id, prompt_tokens,
                                              completion_tokens, total_tokens, duration_ms,
                                              tool_count, failed, created_at)
                VALUES (2, 'conv-1', 'msg-1', ?, ?, ?, ?, 0, false, ?)
                """, promptTokens, completionTokens, totalTokens, durationMs, Timestamp.from(createdAt));
    }

    // ———— trace：动态筛选 / 分页 / 总数 ————

    @Test
    @DisplayName("给定三行观测，when无筛选分页，then按called_at倒序且total=3")
    void givenThreeToolCalls_whenFindTraceUnfiltered_thenNewestFirstWithTotal() {
        Instant now = Instant.now();
        insertToolCall("get_quote", false, 100L, now.minusSeconds(3600));
        insertToolCall("get_kline", true, 300L, now.minusSeconds(1800));
        insertToolCall("get_quote", false, 200L, now.minusSeconds(900));

        ObservabilityQueryRepository.TracePage page = repository.findTrace(null, null, null, null, 0, 50);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.rows()).extracting(ToolTraceRow::toolName)
                .containsExactly("get_quote", "get_kline", "get_quote");
        assertThat(page.rows().get(0).failed()).isFalse();
        assertThat(page.rows().get(1).failed()).isTrue();
        assertThat(page.rows().get(0).durationMs()).isEqualTo(200L);
        assertThat(page.rows().get(0).argsJson()).contains("600519");
        assertThat(page.rows().get(0).calledAt()).isNotNull();
    }

    @Test
    @DisplayName("给定工具与失败筛选，when查询trace，then仅命中行返回")
    void givenToolAndFailedFilters_whenFindTrace_thenOnlyMatchingRows() {
        Instant now = Instant.now();
        insertToolCall("get_quote", false, 100L, now.minusSeconds(3600));
        insertToolCall("get_quote", true, 300L, now.minusSeconds(1800));
        insertToolCall("get_kline", true, 200L, now.minusSeconds(900));

        ObservabilityQueryRepository.TracePage byTool = repository.findTrace(null, null, "get_quote", null, 0, 50);
        assertThat(byTool.total()).isEqualTo(2);
        assertThat(byTool.rows()).allMatch(row -> "get_quote".equals(row.toolName()));

        ObservabilityQueryRepository.TracePage failedOnly = repository.findTrace(null, null, null, true, 0, 50);
        assertThat(failedOnly.total()).isEqualTo(2);
        assertThat(failedOnly.rows()).allMatch(ToolTraceRow::failed);

        ObservabilityQueryRepository.TracePage okOnly = repository.findTrace(null, null, null, false, 0, 50);
        assertThat(okOnly.total()).isEqualTo(1);
        assertThat(okOnly.rows()).noneMatch(ToolTraceRow::failed);
    }

    @Test
    @DisplayName("给定from/to时间窗，when查询trace，then仅窗内行返回")
    void givenFromToWindow_whenFindTrace_thenOnlyRowsInside() {
        Instant now = Instant.now();
        insertToolCall("get_quote", false, 100L, now.minusSeconds(5400)); // 90 分钟前
        insertToolCall("get_kline", false, 200L, now.minusSeconds(2700)); // 45 分钟前
        insertToolCall("get_quote", false, 300L, now.minusSeconds(600));  // 10 分钟前

        // 窗 (now-3000s, now-700s)：只含 45 分钟前行（90 分钟在窗前、10 分钟在窗后）
        ObservabilityQueryRepository.TracePage window = repository.findTrace(
                now.minusSeconds(3000), now.minusSeconds(700), null, null, 0, 50);
        assertThat(window.total()).isEqualTo(1);
        assertThat(window.rows().get(0).toolName()).isEqualTo("get_kline");

        // 上界单边（< now-3600s）：只含 90 分钟前行
        ObservabilityQueryRepository.TracePage before = repository.findTrace(
                null, now.minusSeconds(3600), null, null, 0, 50);
        assertThat(before.total()).isEqualTo(1);
        assertThat(before.rows().get(0).toolName()).isEqualTo("get_quote");
    }

    @Test
    @DisplayName("给定分页size=2，when翻页，then首页2行次页1行且total恒3")
    void givenPaging_whenFindTracePages_thenSlicesAndStableTotal() {
        Instant now = Instant.now();
        insertToolCall("t1", false, 100L, now.minusSeconds(3600));
        insertToolCall("t2", false, 200L, now.minusSeconds(1800));
        insertToolCall("t3", false, 300L, now.minusSeconds(900));

        ObservabilityQueryRepository.TracePage first = repository.findTrace(null, null, null, null, 0, 2);
        ObservabilityQueryRepository.TracePage second = repository.findTrace(null, null, null, null, 1, 2);

        assertThat(first.total()).isEqualTo(3);
        assertThat(first.rows()).extracting(ToolTraceRow::toolName).containsExactly("t3", "t2");
        assertThat(second.total()).isEqualTo(3);
        assertThat(second.rows()).extracting(ToolTraceRow::toolName).containsExactly("t1");
    }

    // ———— cost：按日（跨两日）/ 按工具 ————

    @Test
    @DisplayName("给定跨两日的轮观测，when按日聚合，then两桶各自token求和与轮数")
    void givenTurnsAcrossTwoDays_whenTokenUsageByDay_thenTwoBucketsWithSums() {
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, SHANGHAI);
        Instant day1 = now.minus(java.time.Duration.ofDays(1));
        Instant day2 = now.minus(java.time.Duration.ofDays(2));
        insertTurn(100, 40, 140, 500L, day1);
        insertTurn(50, 20, 70, 600L, day1);
        insertTurn(30, 10, 40, 700L, day2);

        List<ObservabilityQueryRepository.DailyTokenUsage> byDay =
                repository.tokenUsageByDay(now.minus(java.time.Duration.ofDays(7)));

        assertThat(byDay).hasSize(2);
        // 桶序=日期升序；桶键与上海时区日界一致（now-N*24h 恒落第 N 个上海日前）
        assertThat(byDay).extracting(ObservabilityQueryRepository.DailyTokenUsage::date).containsExactly(
                today.minusDays(2).toString(), today.minusDays(1).toString());
        ObservabilityQueryRepository.DailyTokenUsage yesterday = byDay.get(1);
        assertThat(yesterday.promptTokens()).isEqualTo(150);
        assertThat(yesterday.completionTokens()).isEqualTo(60);
        assertThat(yesterday.totalTokens()).isEqualTo(210);
        assertThat(yesterday.turns()).isEqualTo(2);
        ObservabilityQueryRepository.DailyTokenUsage twoDaysAgo = byDay.get(0);
        assertThat(twoDaysAgo.promptTokens()).isEqualTo(30);
        assertThat(twoDaysAgo.turns()).isEqualTo(1);
    }

    @Test
    @DisplayName("给定工具调用含null时长，when按工具聚合，thencalls计数与均值仅计非null")
    void givenToolCallsWithNullDuration_whenToolCallStats_thenCallsAndAvgOfNonNull() {
        Instant now = Instant.now();
        insertToolCall("get_quote", false, 100L, now.minusSeconds(3600));
        insertToolCall("get_quote", false, 300L, now.minusSeconds(1800));
        insertToolCall("get_kline", false, null, now.minusSeconds(900));

        List<ObservabilityQueryRepository.ToolCallStat> stats =
                repository.toolCallStats(now.minus(java.time.Duration.ofDays(7)));

        assertThat(stats).hasSize(2);
        ObservabilityQueryRepository.ToolCallStat quote = stats.stream()
                .filter(s -> "get_quote".equals(s.tool())).findFirst().orElseThrow();
        assertThat(quote.calls()).isEqualTo(2);
        assertThat(quote.avgDurationMs()).isEqualTo(200.0);
        ObservabilityQueryRepository.ToolCallStat kline = stats.stream()
                .filter(s -> "get_kline".equals(s.tool())).findFirst().orElseThrow();
        assertThat(kline.calls()).isEqualTo(1);
        assertThat(kline.avgDurationMs()).isNull();
    }

    // ———— latency：percentile_cont 数值断言 ————

    @Test
    @DisplayName("给定五档轮时长100..500，when整体百分位，thenp50=300且p95=480（线性插值）")
    void givenFiveTurnDurations_whenTurnLatency_thenInterpolatedPercentiles() {
        Instant now = Instant.now();
        for (int i = 1; i <= 5; i++) {
            insertTurn(10, 5, 15, (long) (i * 100), now.minusSeconds(60L * i));
        }

        ObservabilityQueryRepository.TurnLatencyStat stat =
                repository.turnLatency(now.minus(java.time.Duration.ofDays(7)));

        assertThat(stat.p50Ms()).isEqualTo(300.0);
        assertThat(stat.p95Ms()).isEqualTo(480.0); // rank=0.95*(5-1)=3.8 → 400+0.8*100
    }

    @Test
    @DisplayName("给定跨两日的轮时长，when按日百分位，then各日独立插值与轮数")
    void givenTurnsByDay_whenTurnLatencyByDay_thenPerDayPercentiles() {
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, SHANGHAI);
        Instant day1 = now.minus(java.time.Duration.ofDays(1));
        Instant day2 = now.minus(java.time.Duration.ofDays(2));
        insertTurn(10, 5, 15, 100L, day1);
        insertTurn(10, 5, 15, 200L, day1);
        insertTurn(10, 5, 15, 300L, day1);
        insertTurn(10, 5, 15, 400L, day2);
        insertTurn(10, 5, 15, 500L, day2);

        List<ObservabilityQueryRepository.DailyLatencyStat> byDay =
                repository.turnLatencyByDay(now.minus(java.time.Duration.ofDays(7)));

        assertThat(byDay).hasSize(2);
        ObservabilityQueryRepository.DailyLatencyStat yesterday = byDay.get(1);
        assertThat(yesterday.date()).isEqualTo(today.minusDays(1).toString());
        assertThat(yesterday.p50Ms()).isEqualTo(200.0);
        assertThat(yesterday.p95Ms()).isEqualTo(290.0); // 0.95*(3-1)=1.9 → 200+0.9*100
        assertThat(yesterday.turns()).isEqualTo(3);
        ObservabilityQueryRepository.DailyLatencyStat twoDaysAgo = byDay.get(0);
        assertThat(twoDaysAgo.p50Ms()).isEqualTo(450.0);
        assertThat(twoDaysAgo.p95Ms()).isEqualTo(495.0); // 0.95*(2-1)=0.95 → 400+0.95*100
        assertThat(twoDaysAgo.turns()).isEqualTo(2);
    }

    @Test
    @DisplayName("给定按工具的调用时长，when按工具百分位，then各工具独立插值与计数")
    void givenToolDurations_whenToolLatencyByTool_thenPerToolPercentiles() {
        Instant now = Instant.now();
        insertToolCall("get_quote", false, 100L, now.minusSeconds(3600));
        insertToolCall("get_quote", false, 200L, now.minusSeconds(1800));
        insertToolCall("get_quote", false, 300L, now.minusSeconds(1700));
        insertToolCall("get_kline", false, 400L, now.minusSeconds(900));

        List<ObservabilityQueryRepository.ToolLatencyStat> byTool =
                repository.toolLatencyByTool(now.minus(java.time.Duration.ofDays(7)));

        assertThat(byTool).hasSize(2);
        ObservabilityQueryRepository.ToolLatencyStat quote = byTool.stream()
                .filter(s -> "get_quote".equals(s.tool())).findFirst().orElseThrow();
        assertThat(quote.p50Ms()).isEqualTo(200.0);
        assertThat(quote.p95Ms()).isEqualTo(290.0);
        assertThat(quote.calls()).isEqualTo(3);
        ObservabilityQueryRepository.ToolLatencyStat kline = byTool.stream()
                .filter(s -> "get_kline".equals(s.tool())).findFirst().orElseThrow();
        assertThat(kline.p50Ms()).isEqualTo(400.0);
        assertThat(kline.p95Ms()).isEqualTo(400.0);
        assertThat(kline.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("给定空表，when全部查询，then空形状不抛异常（percentile为null）")
    void givenEmptyTables_whenAllQueries_thenEmptyShapesWithNullPercentiles() {
        ObservabilityQueryRepository.TracePage page = repository.findTrace(null, null, null, null, 0, 50);
        assertThat(page.total()).isZero();
        assertThat(page.rows()).isEmpty();

        assertThat(repository.tokenUsageByDay(Instant.now())).isEmpty();
        assertThat(repository.toolCallStats(Instant.now())).isEmpty();
        assertThat(repository.turnLatencyByDay(Instant.now())).isEmpty();
        assertThat(repository.toolLatencyByTool(Instant.now())).isEmpty();

        ObservabilityQueryRepository.TurnLatencyStat stat = repository.turnLatency(Instant.now());
        assertThat(stat.p50Ms()).isNull();
        assertThat(stat.p95Ms()).isNull();
    }
}

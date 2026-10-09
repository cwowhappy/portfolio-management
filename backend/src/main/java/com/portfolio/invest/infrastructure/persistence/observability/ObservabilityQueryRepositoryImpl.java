package com.portfolio.invest.infrastructure.persistence.observability;

import com.portfolio.invest.domain.observability.ObservabilityQueryRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 观测两表看板查询实现（tool_invocation_obs / turn_observation，V5）。照 PushLog 先例
 * 全走 JdbcTemplate 原生 SQL（聚合 percentile_cont/按日分组无 ORM 需求，不建 JPA 门面），
 * 沿 Task 6 EvalRunRepositoryImpl 形态。「按日」桶键以 {@code AT TIME ZONE 'Asia/Shanghai'}
 * 显式钉死（不依赖会话时区）；percentile/avg 均忽略 null 时长，空集聚合返回 null 而非 0。
 *
 * <p>trace 动态筛选（from/to/tool/failed）以「占位符拼接 + 参数化值」构造 WHERE——用户
 * 输入恒经绑定参数，绝不字符串内插。表写入方为 Task 8/9 的 ObservabilityRecorder（另一
 * 实现类），本类只读。
 */
@Repository
public class ObservabilityQueryRepositoryImpl implements ObservabilityQueryRepository {

    /** 日界时区（与调度 cron/产品口径一致）。 */
    private static final String DAY_ZONE = "Asia/Shanghai";

    private static final String TRACE_COLS = """
            SELECT id, user_id, conversation_id, message_id, tool_name, args, result_text,
                   spec_count, as_of, as_of_kind, mcp, failed, duration_ms, called_at
              FROM tool_invocation_obs
            """;

    private static final RowMapper<ToolTraceRow> TRACE_ROW = (rs, i) -> new ToolTraceRow(
            rs.getLong("id"),
            (Long) rs.getObject("user_id"),
            rs.getString("conversation_id"),
            rs.getString("message_id"),
            rs.getString("tool_name"),
            rs.getString("args"),
            rs.getString("result_text"),
            rs.getInt("spec_count"),
            rs.getString("as_of"),
            rs.getString("as_of_kind"),
            rs.getBoolean("mcp"),
            rs.getBoolean("failed"),
            (Long) rs.getObject("duration_ms"),
            toInstant(rs.getTimestamp("called_at")));

    private static final RowMapper<DailyTokenUsage> DAY_USAGE = (rs, i) -> new DailyTokenUsage(
            rs.getString("day"),
            rs.getLong("prompt_tokens"),
            rs.getLong("completion_tokens"),
            rs.getLong("total_tokens"),
            rs.getLong("turns"));

    private static final RowMapper<ToolCallStat> CALL_STAT = (rs, i) -> new ToolCallStat(
            rs.getString("tool_name"), rs.getLong("calls"), nullableDouble(rs, "avg_duration"));

    private static final RowMapper<TurnLatencyStat> TURN_STAT = (rs, i) -> new TurnLatencyStat(
            nullableDouble(rs, "p50"), nullableDouble(rs, "p95"));

    private static final RowMapper<DailyLatencyStat> DAY_LATENCY = (rs, i) -> new DailyLatencyStat(
            rs.getString("day"), nullableDouble(rs, "p50"), nullableDouble(rs, "p95"), rs.getLong("turns"));

    private static final RowMapper<ToolLatencyStat> TOOL_LATENCY = (rs, i) -> new ToolLatencyStat(
            rs.getString("tool_name"), nullableDouble(rs, "p50"), nullableDouble(rs, "p95"),
            rs.getLong("calls"));

    private final JdbcTemplate jdbc;

    public ObservabilityQueryRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public TracePage findTrace(Instant from, Instant to, String tool, Boolean failed,
                               int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (from != null) {
            where.append(" AND called_at >= ?");
            params.add(Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND called_at < ?");
            params.add(Timestamp.from(to));
        }
        if (tool != null && !tool.isBlank()) {
            where.append(" AND tool_name = ?");
            params.add(tool.trim());
        }
        if (failed != null) {
            where.append(" AND failed = ?");
            params.add(failed);
        }
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM tool_invocation_obs" + where, Long.class, params.toArray());
        params.add(size);
        // long 乘法防 int 溢出（审查 M2：溢出为负 OFFSET 即 500；web 层另有 @Max 护栏双保险）
        params.add((long) page * size);
        List<ToolTraceRow> rows = jdbc.query(
                TRACE_COLS + where + " ORDER BY called_at DESC, id DESC LIMIT ? OFFSET ?",
                TRACE_ROW, params.toArray());
        return new TracePage(rows, total == null ? 0 : total);
    }

    @Override
    public List<DailyTokenUsage> tokenUsageByDay(Instant since) {
        return jdbc.query("""
                SELECT to_char(created_at AT TIME ZONE '%s', 'YYYY-MM-DD') AS day,
                       coalesce(sum(prompt_tokens), 0)::bigint AS prompt_tokens,
                       coalesce(sum(completion_tokens), 0)::bigint AS completion_tokens,
                       coalesce(sum(total_tokens), 0)::bigint AS total_tokens,
                       count(*)::bigint AS turns
                  FROM turn_observation
                 WHERE created_at >= ?
                 GROUP BY 1
                 ORDER BY 1
                """.formatted(DAY_ZONE), DAY_USAGE, Timestamp.from(since));
    }

    @Override
    public List<ToolCallStat> toolCallStats(Instant since) {
        return jdbc.query("""
                SELECT tool_name, count(*)::bigint AS calls, avg(duration_ms) AS avg_duration
                  FROM tool_invocation_obs
                 WHERE called_at >= ?
                 GROUP BY tool_name
                 ORDER BY tool_name
                """, CALL_STAT, Timestamp.from(since));
    }

    @Override
    public TurnLatencyStat turnLatency(Instant since) {
        // 无 GROUP BY 的聚合恒返回一行（空集时 percentile 为 null），不会抛 EmptyResultDataAccessException。
        // 不加 duration_ms IS NOT NULL：percentile_cont 本就忽略 null，谓词只会把 null 行挡在
        // 同查询的 count 之外（审查 I1——calls/turns 计数须与 cost 端点同母体，含 null 时长行）。
        return jdbc.queryForObject("""
                SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms) AS p50,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95
                  FROM turn_observation
                 WHERE created_at >= ?
                """, TURN_STAT, Timestamp.from(since));
    }

    @Override
    public List<DailyLatencyStat> turnLatencyByDay(Instant since) {
        return jdbc.query("""
                SELECT to_char(created_at AT TIME ZONE '%s', 'YYYY-MM-DD') AS day,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms) AS p50,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95,
                       count(*)::bigint AS turns
                  FROM turn_observation
                 WHERE created_at >= ?
                 GROUP BY 1
                 ORDER BY 1
                """.formatted(DAY_ZONE), DAY_LATENCY, Timestamp.from(since));
    }

    @Override
    public List<ToolLatencyStat> toolLatencyByTool(Instant since) {
        return jdbc.query("""
                SELECT tool_name,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms) AS p50,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95,
                       count(*)::bigint AS calls
                  FROM tool_invocation_obs
                 WHERE called_at >= ?
                 GROUP BY tool_name
                 ORDER BY tool_name
                """, TOOL_LATENCY, Timestamp.from(since));
    }

    // ———— 读装配 helpers ————

    private static Double nullableDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}

package com.portfolio.invest.infrastructure.persistence.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.intelligence.ExtractStatus;
import com.portfolio.invest.domain.intelligence.PolicyConfidence;
import com.portfolio.invest.domain.intelligence.PolicyDirection;
import com.portfolio.invest.domain.intelligence.PolicyEvent;
import com.portfolio.invest.domain.intelligence.PolicyExtractResult;
import com.portfolio.invest.domain.intelligence.PolicyRecord;
import com.portfolio.invest.domain.intelligence.PolicyRepository;
import com.portfolio.invest.domain.intelligence.PolicyStrength;
import com.portfolio.invest.domain.intelligence.PageQuery;
import com.portfolio.invest.domain.intelligence.PageResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 政策域仓库实现（intelligence_policy_raw 连 intelligence_policy_event）。
 *
 * <p>三方法全走 JdbcTemplate 原生 SQL，照 {@link IntelligenceNewsRepositoryImpl} 先例：
 * trgm 相似度算子（{@code %}）、JSONB 列与 INSERT … ON CONFLICT 均为 PG 方言，JPQL 无法
 * 表达。分页为 OFFSET/LIMIT + 独立 COUNT 双查询（D20）。日界按 Asia/Shanghai 折算。
 * <b>长期保留</b>：无删除方法（FK 无级联，政策不滚动清理）。事务边界在 application 层。
 */
@Repository
public class IntelligencePolicyRepositoryImpl implements PolicyRepository {

    /** 市场时区（日界折算口径，与调度任务 zone 一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 合并视图列（searchEvents / findPendingForExtraction 共用形态，后者只取 raw 侧+状态）。 */
    private static final String EVENT_SELECT_COLS = """
            SELECT e.id, e.policy_raw_id, r.source, r.external_id, r.title, r.url, r.published_at,
                   e.direction, e.strength, e.affected_areas, e.summary, e.confidence,
                   e.status, e.model, e.extracted_at
            """;

    private static final String RAW_JOIN_EVENT = """
            FROM intelligence_policy_raw r
            JOIN intelligence_policy_event e ON e.policy_raw_id = r.id
            """;

    private static final String RAW_LEFT_JOIN_EVENT = """
            FROM intelligence_policy_raw r
            LEFT JOIN intelligence_policy_event e ON e.policy_raw_id = r.id
            """;

    /** 事件合并视图行映射（isPolicy 经哨兵 summary 派生，见 PolicyEvent#of）。 */
    private static final RowMapper<PolicyEvent> EVENT_ROW_MAPPER = (rs, i) -> PolicyEvent.of(
            rs.getLong("id"), rs.getLong("policy_raw_id"), rs.getString("source"),
            rs.getString("external_id"), rs.getString("title"), rs.getString("url"),
            instant(rs, "published_at"),
            enumOf(rs, "direction", PolicyDirection.class),
            enumOf(rs, "strength", PolicyStrength.class),
            stringList(rs, "affected_areas"), rs.getString("summary"),
            enumOf(rs, "confidence", PolicyConfidence.class),
            enumOf(rs, "status", ExtractStatus.class), rs.getString("model"), instant(rs, "extracted_at"));

    /** raw 行读取映射（抽取批消费：raw 侧字段 + 合并抽取状态）。 */
    private static final RowMapper<PolicyRecord> RECORD_ROW_MAPPER = (rs, i) -> new PolicyRecord(
            rs.getLong("id"), rs.getString("source"), rs.getString("external_id"),
            rs.getString("title"), rs.getString("url"), instant(rs, "published_at"),
            rs.getString("content_text"), enumOf(rs, "status", ExtractStatus.class));

    private final JdbcTemplate jdbc;

    public IntelligencePolicyRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PageResult<PolicyEvent> searchEvents(PageQuery q) {
        // 仅 SUCCESS：PENDING/FAILED 为抽取中间态不出检索面（isPolicy=false 兜底行为 SUCCESS 可见）
        StringBuilder where = new StringBuilder(" WHERE e.status = 'SUCCESS'");
        List<Object> args = new ArrayList<>();
        if (q.keyword() != null && !q.keyword().isBlank()) {
            // LIKE 保底子串命中 + trgm 容错近似（两算子均走 PG 方言）
            where.append(" AND (r.title LIKE ? OR r.title % ?)");
            args.add("%" + q.keyword().trim() + "%");
            args.add(q.keyword().trim());
        }
        if (q.from() != null) {
            where.append(" AND r.published_at >= ?");
            args.add(dayStart(q.from()));
        }
        if (q.to() != null) {
            where.append(" AND r.published_at < ?"); // to 为闭区间右端：次日起始即排除点
            args.add(dayStart(q.to().plusDays(1)));
        }
        if (q.direction() != null) {
            where.append(" AND e.direction = ?");
            args.add(q.direction().name());
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*)" + RAW_JOIN_EVENT + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(q.pageSize());
        pageArgs.add((long) (q.page() - 1) * q.pageSize());
        List<PolicyEvent> items = jdbc.query(
                EVENT_SELECT_COLS + RAW_JOIN_EVENT + where
                        + " ORDER BY r.published_at DESC, r.id DESC LIMIT ? OFFSET ?",
                EVENT_ROW_MAPPER, pageArgs.toArray());

        return new PageResult<>(items, total == null ? 0 : total, q.page(), q.pageSize());
    }

    @Override
    public List<PolicyRecord> findPendingForExtraction(LocalDate day, int lookbackDays, int limit) {
        // 游标窗口：lookbackDays 个自然日含当日（day-（lookbackDays-1）起至 day 止），下界兜底 1（仅当日）；
        // 判定列 published_at（政策 raw 无 fetched_at 入库列，区别于新闻/公告）
        LocalDate windowStart = day.minusDays(Math.max(1, lookbackDays) - 1L);
        return jdbc.query("""
                        SELECT r.id, r.source, r.external_id, r.title, r.url, r.published_at,
                               r.content_text, e.status
                        """ + RAW_LEFT_JOIN_EVENT + """
                          WHERE r.published_at >= ? AND r.published_at < ?
                            AND (e.id IS NULL OR e.status = 'PENDING')
                          ORDER BY r.id
                          LIMIT ?
                        """,
                RECORD_ROW_MAPPER, dayStart(windowStart), dayStart(day.plusDays(1)), limit);
    }

    @Override
    public void upsertExtract(Long policyRawId, PolicyExtractResult result) {
        // 无论旧状态一律整体置换；无既有行即插入（首次 upsert 建行，UNIQUE(policy_raw_id) 兜底并发）
        jdbc.update("""
                INSERT INTO intelligence_policy_event
                    (policy_raw_id, direction, strength, affected_areas, summary,
                     confidence, status, model, extracted_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                ON CONFLICT (policy_raw_id) DO UPDATE SET
                    direction = EXCLUDED.direction, strength = EXCLUDED.strength,
                    affected_areas = EXCLUDED.affected_areas, summary = EXCLUDED.summary,
                    confidence = EXCLUDED.confidence, status = EXCLUDED.status,
                    model = EXCLUDED.model, extracted_at = EXCLUDED.extracted_at
                """,
                policyRawId,
                result.direction() == null ? null : result.direction().name(),
                result.strength() == null ? null : result.strength().name(),
                toJson(result.affectedAreas()),
                result.summary(),
                result.confidence() == null ? null : result.confidence().name(),
                result.status().name(), result.model(),
                offsetDateTimeOf(result.extractedAt() == null ? Instant.now() : result.extractedAt()));
    }

    // ── 私有助手 ─────────────────────────────────────────────────

    /** LocalDate 当日零点（Asia/Shanghai）→ Instant → OffsetDateTime（PG timestamptz 参数绑定）。 */
    private static OffsetDateTime dayStart(LocalDate day) {
        return offsetDateTimeOf(day.atStartOfDay(ZONE).toInstant());
    }

    private static OffsetDateTime offsetDateTimeOf(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** JSONB 列读为字符串列表（null 列归一为空列表）。 */
    private static List<String> stringList(ResultSet rs, String column) throws SQLException {
        String json = rs.getString(column);
        if (json == null) {
            return List.of();
        }
        try {
            return List.of(JSON.readValue(json, String[].class));
        } catch (Exception e) {
            // 契约列由本仓库独占写入，解析失败即数据异常——显式抛出而非静默吞
            throw new SQLException("JSONB 列解析失败: " + column + " = " + json, e);
        }
    }

    private static <E extends Enum<E>> E enumOf(ResultSet rs, String column, Class<E> type)
            throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static String toJson(List<String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 参数序列化失败", e);
        }
    }
}

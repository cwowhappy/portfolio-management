package com.portfolio.invest.infrastructure.persistence.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.intelligence.Direction;
import com.portfolio.invest.domain.intelligence.ExtractStatus;
import com.portfolio.invest.domain.intelligence.NewsExtractResult;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import com.portfolio.invest.domain.intelligence.NewsRepository;
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
 * 新闻域仓库实现（intelligence_news_raw 左连 intelligence_news_extract）。
 *
 * <p>六方法全走 JdbcTemplate 原生 SQL，照 ScreeningRepositoryImpl 的动态拼接先例
 * （StringBuilder + {@code ?} 参数绑定，用户输入绝不拼接进 SQL 文本）：trgm 相似度
 * 算子（{@code %}）、JSONB 包含（{@code @>}）与 INSERT … ON CONFLICT 均为 PG 方言，
 * JPQL 无法表达。分页为 OFFSET/LIMIT + 独立 COUNT 双查询（D20）。日界按
 * Asia/Shanghai 折算（市场时区，照 MarketDataParser 先例）。事务边界在 application 层。
 */
@Repository
public class IntelligenceNewsRepositoryImpl implements NewsRepository {

    /** 市场时区（日界折算口径，与调度任务 zone 一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SELECT_COLS = """
            SELECT r.id, r.source, r.external_id, r.title, r.summary AS raw_summary,
                   r.published_at, r.url, r.stock_tags, r.fetched_at,
                   e.event_type, e.stock_codes, e.industry_codes, e.summary AS extract_summary,
                   e.direction, e.key_numbers, e.importance, e.status, e.model, e.extracted_at
            """;

    private static final String RAW_LEFT_JOIN_EXTRACT = """
            FROM intelligence_news_raw r
            LEFT JOIN intelligence_news_extract e ON e.news_raw_id = r.id
            """;

    /** 合并视图行映射（search / findPendingForExtraction / findMajorSince 共用）。 */
    private static final RowMapper<NewsRecord> NEWS_ROW_MAPPER = (rs, i) -> new NewsRecord(
            rs.getLong("id"), rs.getString("source"), rs.getString("external_id"),
            rs.getString("title"), rs.getString("raw_summary"),
            instant(rs, "published_at"), rs.getString("url"), rs.getString("stock_tags"),
            instant(rs, "fetched_at"),
            rs.getString("event_type"), stringList(rs, "stock_codes"), stringList(rs, "industry_codes"),
            rs.getString("extract_summary"), enumOf(rs, "direction", Direction.class),
            stringList(rs, "key_numbers"),
            rs.getObject("importance") == null ? null : rs.getInt("importance"),
            enumOf(rs, "status", ExtractStatus.class), rs.getString("model"), instant(rs, "extracted_at"));

    private final JdbcTemplate jdbc;

    public IntelligenceNewsRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PageResult<NewsRecord> search(PageQuery q) {
        // 动态过滤（可空条件逐一追加，参数顺序与占位符一致）
        StringBuilder where = new StringBuilder(" WHERE true");
        List<Object> args = new ArrayList<>();
        if (q.keyword() != null && !q.keyword().isBlank()) {
            // LIKE 保底子串命中 + trgm 容错近似（两算子均被 GIN trgm 索引加速）
            where.append(" AND (r.title LIKE ? OR r.title % ?)");
            args.add("%" + q.keyword().trim() + "%");
            args.add(q.keyword().trim());
        }
        if (q.stockCode() != null && !q.stockCode().isBlank()) {
            where.append(" AND e.stock_codes @> ?::jsonb");
            args.add(toJson(List.of(q.stockCode().trim())));
        }
        if (q.industryCode() != null && !q.industryCode().isBlank()) {
            where.append(" AND e.industry_codes @> ?::jsonb");
            args.add(toJson(List.of(q.industryCode().trim())));
        }
        if (q.from() != null) {
            where.append(" AND r.published_at >= ?");
            args.add(dayStart(q.from()));
        }
        if (q.to() != null) {
            where.append(" AND r.published_at < ?"); // to 为闭区间右端：次日起始即排除点
            args.add(dayStart(q.to().plusDays(1)));
        }
        if (q.minImportance() != null) {
            where.append(" AND e.importance >= ?");
            args.add(q.minImportance());
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*)" + RAW_LEFT_JOIN_EXTRACT + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(q.pageSize());
        pageArgs.add((long) (q.page() - 1) * q.pageSize());
        List<NewsRecord> items = jdbc.query(
                SELECT_COLS + RAW_LEFT_JOIN_EXTRACT + where
                        + " ORDER BY r.published_at DESC, r.id DESC LIMIT ? OFFSET ?",
                NEWS_ROW_MAPPER, pageArgs.toArray());

        return new PageResult<>(items, total == null ? 0 : total, q.page(), q.pageSize());
    }

    @Override
    public List<NewsRecord> findPendingForExtraction(LocalDate day, int lookbackDays, int limit) {
        // 游标窗口：lookbackDays 个自然日含当日（day-（lookbackDays-1）起至 day 止），下界兜底 1（仅当日）
        LocalDate windowStart = day.minusDays(Math.max(1, lookbackDays) - 1L);
        return jdbc.query(
                SELECT_COLS + RAW_LEFT_JOIN_EXTRACT + """
                          WHERE r.fetched_at >= ? AND r.fetched_at < ?
                            AND (e.id IS NULL OR e.status = 'PENDING')
                          ORDER BY r.id
                          LIMIT ?
                        """,
                NEWS_ROW_MAPPER, dayStart(windowStart), dayStart(day.plusDays(1)), limit);
    }

    @Override
    public long countPendingInWindow(LocalDate day, int lookbackDays) {
        // 与 findPendingForExtraction 同窗口同 PENDING 口径，仅去 LIMIT 换 COUNT（真实剩余量不受批大小截断）
        LocalDate windowStart = day.minusDays(Math.max(1, lookbackDays) - 1L);
        Long count = jdbc.queryForObject(
                "SELECT count(*)" + RAW_LEFT_JOIN_EXTRACT + """
                          WHERE r.fetched_at >= ? AND r.fetched_at < ?
                            AND (e.id IS NULL OR e.status = 'PENDING')
                        """,
                Long.class, dayStart(windowStart), dayStart(day.plusDays(1)));
        return count == null ? 0 : count;
    }

    @Override
    public void upsertExtract(Long newsRawId, NewsExtractResult result) {
        // 无论旧状态一律整体置换；无既有行即插入（首次 upsert 建行，UNIQUE(news_raw_id) 兜底并发）
        jdbc.update("""
                INSERT INTO intelligence_news_extract
                    (news_raw_id, event_type, stock_codes, industry_codes, summary,
                     direction, key_numbers, importance, status, model, extracted_at)
                VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (news_raw_id) DO UPDATE SET
                    event_type = EXCLUDED.event_type, stock_codes = EXCLUDED.stock_codes,
                    industry_codes = EXCLUDED.industry_codes, summary = EXCLUDED.summary,
                    direction = EXCLUDED.direction, key_numbers = EXCLUDED.key_numbers,
                    importance = EXCLUDED.importance, status = EXCLUDED.status,
                    model = EXCLUDED.model, extracted_at = EXCLUDED.extracted_at
                """,
                newsRawId, result.eventType(), toJson(result.stockCodes()), toJson(result.industryCodes()),
                result.summary(), result.direction() == null ? null : result.direction().name(),
                toJson(result.keyNumbers()), result.importance(), result.status().name(),
                result.model(),
                offsetDateTimeOf(result.extractedAt() == null ? Instant.now() : result.extractedAt()));
    }

    @Override
    public long deleteRawBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM intelligence_news_raw WHERE published_at < ?",
                offsetDateTimeOf(cutoff));
    }

    @Override
    public long countExtractedByDate(LocalDate day) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_news_extract WHERE extracted_at >= ? AND extracted_at < ?",
                Long.class, dayStart(day), dayStart(day.plusDays(1)));
        return count == null ? 0 : count;
    }

    @Override
    public List<NewsRecord> findMajorSince(Instant since, int majorAt) {
        return jdbc.query(
                SELECT_COLS + """
                          FROM intelligence_news_raw r
                          JOIN intelligence_news_extract e ON e.news_raw_id = r.id
                          WHERE r.published_at >= ? AND e.status = 'SUCCESS' AND e.importance >= ?
                          ORDER BY r.published_at DESC, r.id DESC
                        """,
                NEWS_ROW_MAPPER, offsetDateTimeOf(since), majorAt);
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

    /** JSONB 列读为字符串列表（jsonb 文本形如 ["600519"]；null 列归一为空列表）。 */
    private static List<String> stringList(ResultSet rs, String column) throws SQLException {
        String json = rs.getString(column);
        if (json == null) {
            return List.of();
        }
        try {
            return List.of(JSON.readValue(json, String[].class));
        } catch (Exception e) {
            // 契约列由本仓库/采集侧独占写入，解析失败即数据异常——显式抛出而非静默吞
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

package com.portfolio.invest.infrastructure.persistence.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.intelligence.AnnouncementExtractResult;
import com.portfolio.invest.domain.intelligence.AnnouncementMetrics;
import com.portfolio.invest.domain.intelligence.AnnouncementRecord;
import com.portfolio.invest.domain.intelligence.AnnouncementRepository;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import com.portfolio.invest.domain.intelligence.ExtractStatus;
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
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 公告域仓库实现（intelligence_announcement 左连 intelligence_announcement_extract）。
 *
 * <p>五方法全走 JdbcTemplate 原生 SQL，照 {@link IntelligenceNewsRepositoryImpl} 先例
 * （StringBuilder + {@code ?} 参数绑定，用户输入绝不拼接进 SQL 文本）：trgm 相似度算子
 * （{@code %}）、JSONB 包含（{@code @>}）与 INSERT … ON CONFLICT 均为 PG 方言，JPQL 无法
 * 表达。分页为 OFFSET/LIMIT + 独立 COUNT 双查询（D20）。日界按 Asia/Shanghai 折算
 * （市场时区，照 MarketDataParser 先例）。事务边界在 application 层。
 */
@Repository
public class IntelligenceAnnouncementRepositoryImpl implements AnnouncementRepository {

    /** 市场时区（日界折算口径，与调度任务 zone 一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SELECT_COLS = """
            SELECT r.id, r.source, r.external_id, r.stock_code, r.stock_name, r.title,
                   r.ann_type_source, r.major, r.published_at, r.pdf_url, r.fetched_at,
                   e.metrics, e.ann_types, e.pdf_text, e.status, e.model, e.extracted_at
            """;

    private static final String ANNOUNCEMENT_LEFT_JOIN_EXTRACT = """
            FROM intelligence_announcement r
            LEFT JOIN intelligence_announcement_extract e ON e.announcement_id = r.id
            """;

    /** 合并视图行映射（search / findPendingForExtraction / findExtractedMajorSince 共用）。 */
    private static final RowMapper<AnnouncementRecord> ANNOUNCEMENT_ROW_MAPPER = (rs, i) ->
            new AnnouncementRecord(
                    rs.getLong("id"), rs.getString("source"), rs.getString("external_id"),
                    rs.getString("stock_code"), rs.getString("stock_name"), rs.getString("title"),
                    rs.getString("ann_type_source"), rs.getBoolean("major"),
                    instant(rs, "published_at"), rs.getString("pdf_url"), instant(rs, "fetched_at"),
                    metrics(rs, "metrics"), enumList(rs, "ann_types", AnnouncementType.class),
                    rs.getString("pdf_text"), enumOf(rs, "status", ExtractStatus.class),
                    rs.getString("model"), instant(rs, "extracted_at"));

    private final JdbcTemplate jdbc;

    public IntelligenceAnnouncementRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PageResult<AnnouncementRecord> search(PageQuery q) {
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
            // 公告表直列等值（区别于新闻的抽取侧 JSONB 包含）
            where.append(" AND r.stock_code = ?");
            args.add(q.stockCode().trim());
        }
        if (q.type() != null) {
            // ann_types 在 extract 侧：未抽取条目无标签，type 过滤不命中（语义正确）
            where.append(" AND e.ann_types @> ?::jsonb");
            args.add(toJson(List.of(q.type().name())));
        }
        if (q.from() != null) {
            where.append(" AND r.published_at >= ?");
            args.add(dayStart(q.from()));
        }
        if (q.to() != null) {
            where.append(" AND r.published_at < ?"); // to 为闭区间右端：次日起始即排除点
            args.add(dayStart(q.to().plusDays(1)));
        }
        if (q.major() != null) {
            where.append(" AND r.major = ?");
            args.add(q.major());
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*)" + ANNOUNCEMENT_LEFT_JOIN_EXTRACT + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(q.pageSize());
        pageArgs.add((long) (q.page() - 1) * q.pageSize());
        List<AnnouncementRecord> items = jdbc.query(
                SELECT_COLS + ANNOUNCEMENT_LEFT_JOIN_EXTRACT + where
                        + " ORDER BY r.published_at DESC, r.id DESC LIMIT ? OFFSET ?",
                ANNOUNCEMENT_ROW_MAPPER, pageArgs.toArray());

        return new PageResult<>(items, total == null ? 0 : total, q.page(), q.pageSize());
    }

    @Override
    public List<AnnouncementRecord> findPendingForExtraction(LocalDate day, int lookbackDays, int limit) {
        // 游标窗口：lookbackDays 个自然日含当日（day-（lookbackDays-1）起至 day 止），下界兜底 1（仅当日）；
        // 与新闻同款 3 日窗语义——major/业绩类与否由抽取服务（Task 5）预筛，仓库不管业务筛选
        LocalDate windowStart = day.minusDays(Math.max(1, lookbackDays) - 1L);
        return jdbc.query(
                SELECT_COLS + ANNOUNCEMENT_LEFT_JOIN_EXTRACT + """
                          WHERE r.fetched_at >= ? AND r.fetched_at < ?
                            AND (e.id IS NULL OR e.status = 'PENDING')
                          ORDER BY r.id
                          LIMIT ?
                        """,
                ANNOUNCEMENT_ROW_MAPPER, dayStart(windowStart), dayStart(day.plusDays(1)), limit);
    }

    @Override
    public void upsertExtract(Long announcementId, AnnouncementExtractResult result) {
        // 无论旧状态一律整体置换；无既有行即插入（首次 upsert 建行，UNIQUE(announcement_id) 兜底并发）
        jdbc.update("""
                INSERT INTO intelligence_announcement_extract
                    (announcement_id, metrics, ann_types, pdf_text, status, model, extracted_at)
                VALUES (?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (announcement_id) DO UPDATE SET
                    metrics = EXCLUDED.metrics, ann_types = EXCLUDED.ann_types,
                    pdf_text = EXCLUDED.pdf_text, status = EXCLUDED.status,
                    model = EXCLUDED.model, extracted_at = EXCLUDED.extracted_at
                """,
                announcementId,
                result.metrics() == null ? null : toJson(result.metrics()),
                toJson(result.annTypes()),
                result.pdfText(), result.status().name(), result.model(),
                offsetDateTimeOf(result.extractedAt() == null ? Instant.now() : result.extractedAt()));
    }

    @Override
    public List<AnnouncementRecord> findExtractedMajorSince(Instant since) {
        // 推送消费口径：按 extracted_at（抽取时间轴）而非 published_at——晚间批抽取的当日盘后
        // 公告与跨日续抽的积压条目都命中；已推送与否由 push_log 查重，仓库不管推送幂等
        return jdbc.query(
                SELECT_COLS + """
                          FROM intelligence_announcement r
                          JOIN intelligence_announcement_extract e ON e.announcement_id = r.id
                          WHERE e.extracted_at >= ? AND e.status = 'SUCCESS' AND r.major = true
                          ORDER BY e.extracted_at DESC, r.id DESC
                          """,
                ANNOUNCEMENT_ROW_MAPPER, offsetDateTimeOf(since));
    }

    @Override
    public boolean existsExtract(Long announcementId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM intelligence_announcement_extract WHERE announcement_id = ?)",
                Boolean.class, announcementId);
        return Boolean.TRUE.equals(exists);
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

    /** metrics JSONB 列读为六字段契约（null 列归 null；未披露字段 null 且进 undisclosed）。 */
    private static AnnouncementMetrics metrics(ResultSet rs, String column) throws SQLException {
        String json = rs.getString(column);
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, AnnouncementMetrics.class);
        } catch (Exception e) {
            // 契约列由本仓库独占写入，解析失败即数据异常——显式抛出而非静默吞
            throw new SQLException("JSONB 列解析失败: " + column + " = " + json, e);
        }
    }

    /** JSONB 列读为枚举名列表（jsonb 文本形如 ["BUYBACK"]；null 列归一为空列表）。 */
    private static <E extends Enum<E>> List<E> enumList(ResultSet rs, String column, Class<E> type)
            throws SQLException {
        String json = rs.getString(column);
        if (json == null) {
            return List.of();
        }
        try {
            return Arrays.stream(JSON.readValue(json, String[].class))
                    .map(name -> Enum.valueOf(type, name))
                    .toList();
        } catch (Exception e) {
            throw new SQLException("JSONB 列解析失败: " + column + " = " + json, e);
        }
    }

    private static <E extends Enum<E>> E enumOf(ResultSet rs, String column, Class<E> type)
            throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 参数序列化失败", e);
        }
    }
}

package com.portfolio.invest.infrastructure.persistence.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.intelligence.BriefRepository;
import com.portfolio.invest.domain.intelligence.BriefStatus;
import com.portfolio.invest.domain.intelligence.DailyBrief;
import com.portfolio.invest.domain.intelligence.PageQuery;
import com.portfolio.invest.domain.intelligence.PageResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 盘前简报仓库实现（intelligence_daily_brief，一行一交易日）。
 *
 * <p>照 {@link IntelligenceNewsRepositoryImpl} 先例全走 JdbcTemplate 原生 SQL：top_stocks
 * 为 PG JSONB 列（{@code ?::jsonb} 写入、文本读出后 Jackson 反序列化；检索侧
 * {@code @>} contains 命中），save 为 INSERT … ON CONFLICT (trade_date) 整体置换——
 * 服务层幂等守卫之外兜底并发双触发（本仓库不建 JPA 实体门面：写入仅此一处，Task 5
 * 遗留的「JPA 门面无运行时消费者」教训不再复制）。search 的 keyword 走 content_md
 * trgm 双臂（LIKE 子串 + {@code %} 容错，均被 GIN trgm 索引加速）。事务边界在
 * application 层。
 */
@Repository
public class IntelligenceDailyBriefRepositoryImpl implements BriefRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SELECT_COLS = """
            SELECT id, trade_date, content_md, top_stocks, status, fail_reason, model, generated_at
            """;

    /** 归档行映射（findByDate / search 共用）。 */
    private static final RowMapper<DailyBrief> BRIEF_ROW_MAPPER = (rs, i) -> new DailyBrief(
            rs.getLong("id"), rs.getDate("trade_date").toLocalDate(),
            rs.getString("content_md"), stringList(rs, "top_stocks"),
            BriefStatus.valueOf(rs.getString("status")), rs.getString("fail_reason"),
            rs.getString("model"), instant(rs, "generated_at"));

    private final JdbcTemplate jdbc;

    public IntelligenceDailyBriefRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(DailyBrief brief) {
        jdbc.update("""
                INSERT INTO intelligence_daily_brief
                    (trade_date, content_md, top_stocks, status, fail_reason, model, generated_at)
                VALUES (?, ?, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (trade_date) DO UPDATE SET
                    content_md = EXCLUDED.content_md, top_stocks = EXCLUDED.top_stocks,
                    status = EXCLUDED.status, fail_reason = EXCLUDED.fail_reason,
                    model = EXCLUDED.model, generated_at = EXCLUDED.generated_at
                """,
                brief.tradeDate(), brief.contentMd(), toJson(brief.topStocks()),
                brief.status().name(), brief.failReason(), brief.model(),
                offsetDateTimeOf(brief.generatedAt()));
    }

    @Override
    public Optional<DailyBrief> findByDate(LocalDate tradeDate) {
        List<DailyBrief> rows = jdbc.query(
                SELECT_COLS + " FROM intelligence_daily_brief WHERE trade_date = ?",
                BRIEF_ROW_MAPPER, tradeDate);
        return rows.stream().findFirst();
    }

    @Override
    public PageResult<DailyBrief> search(PageQuery q) {
        // 动态过滤（可空条件逐一追加，参数顺序与占位符一致）；trade_date 为 DATE 直比闭区间
        StringBuilder where = new StringBuilder(" WHERE true");
        List<Object> args = new ArrayList<>();
        if (q.keyword() != null && !q.keyword().isBlank()) {
            // LIKE 保底子串命中 + trgm 容错近似（两算子均被 content_md GIN trgm 索引加速）
            where.append(" AND (content_md LIKE ? OR content_md % ?)");
            args.add("%" + q.keyword().trim() + "%");
            args.add(q.keyword().trim());
        }
        if (q.stockCode() != null && !q.stockCode().isBlank()) {
            // 决策 #23 检索维度：top_stocks JSONB contains（空简版/失败版无快照不命中）
            where.append(" AND top_stocks @> ?::jsonb");
            args.add(toJson(List.of(q.stockCode().trim())));
        }
        if (q.from() != null) {
            where.append(" AND trade_date >= ?");
            args.add(java.sql.Date.valueOf(q.from()));
        }
        if (q.to() != null) {
            where.append(" AND trade_date <= ?"); // DATE 直比：to 当日含（闭区间右端即含）
            args.add(java.sql.Date.valueOf(q.to()));
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_daily_brief" + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(q.pageSize());
        pageArgs.add((long) (q.page() - 1) * q.pageSize());
        List<DailyBrief> items = jdbc.query(
                SELECT_COLS + " FROM intelligence_daily_brief" + where
                        + " ORDER BY trade_date DESC LIMIT ? OFFSET ?",
                BRIEF_ROW_MAPPER, pageArgs.toArray());

        return new PageResult<>(items, total == null ? 0 : total, q.page(), q.pageSize());
    }

    // ── 私有助手（照 IntelligenceNewsRepositoryImpl 同名助手） ────

    private static OffsetDateTime offsetDateTimeOf(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** JSONB 列读为字符串列表（null 列归一为空列表——空简版/失败版无快照）。 */
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

    private static String toJson(List<String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 参数序列化失败", e);
        }
    }
}

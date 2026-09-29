package com.portfolio.invest.infrastructure.persistence.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.intelligence.BriefRepository;
import com.portfolio.invest.domain.intelligence.BriefStatus;
import com.portfolio.invest.domain.intelligence.DailyBrief;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 盘前简报仓库实现（intelligence_daily_brief，一行一交易日）。
 *
 * <p>照 {@link IntelligenceNewsRepositoryImpl} 先例全走 JdbcTemplate 原生 SQL：top_stocks
 * 为 PG JSONB 列（{@code ?::jsonb} 写入、文本读出后 Jackson 反序列化），save 为
 * INSERT … ON CONFLICT (trade_date) 整体置换——服务层幂等守卫之外兜底并发双触发
 * （本仓库不建 JPA 实体门面：写入仅此一处，Task 5 遗留的「JPA 门面无运行时消费者」
 * 教训不再复制）。事务边界在 application 层。
 */
@Repository
public class IntelligenceDailyBriefRepositoryImpl implements BriefRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SELECT_COLS = """
            SELECT id, trade_date, content_md, top_stocks, status, fail_reason, model, generated_at
            """;

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
                (rs, i) -> new DailyBrief(rs.getLong("id"), rs.getDate("trade_date").toLocalDate(),
                        rs.getString("content_md"), stringList(rs, "top_stocks"),
                        BriefStatus.valueOf(rs.getString("status")), rs.getString("fail_reason"),
                        rs.getString("model"), instant(rs, "generated_at")),
                tradeDate);
        return rows.stream().findFirst();
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

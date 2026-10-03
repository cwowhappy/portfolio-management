package com.portfolio.invest.infrastructure.persistence.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.intelligence.IntelligenceSubscription;
import com.portfolio.invest.domain.intelligence.SubscriptionRepository;
import com.portfolio.invest.domain.intelligence.SubscriptionStock;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * 订阅仓库实现（intelligence_subscription + intelligence_subscription_stock，F16）。
 *
 * <p>照 {@link IntelligenceDailyBriefRepositoryImpl} 先例全走 JdbcTemplate 原生 SQL，
 * 不建 JPA 门面（写入仅 SubscriptionService 一处）：主表 INSERT … ON CONFLICT (user_id)
 * 整体置换；子表差集同步——先删不在新集合的标的，其余逐行 upsert（保留标的 added_at
 * 不动，改名走 DO UPDATE 分支）。industries 为 PG JSONB 列（{@code ?::jsonb} 写入、
 * 文本读出后 Jackson 反序列化）。两表一致性的 @Transactional 边界在 application 层。
 */
@Repository
public class IntelligenceSubscriptionRepositoryImpl implements SubscriptionRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SELECT_MAIN = """
            SELECT user_id, push_enabled, industries, updated_at FROM intelligence_subscription
            """;

    private final JdbcTemplate jdbc;

    public IntelligenceSubscriptionRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IntelligenceSubscription> findByUserId(Long userId) {
        List<MainRow> rows = jdbc.query(SELECT_MAIN + " WHERE user_id = ?",
                (rs, i) -> mainRow(rs), userId);
        return rows.stream().findFirst().map(row -> assemble(row, stocksOf(List.of(userId)).get(userId)));
    }

    @Override
    public IntelligenceSubscription save(IntelligenceSubscription subscription) {
        jdbc.update("""
                INSERT INTO intelligence_subscription (user_id, push_enabled, industries, updated_at)
                VALUES (?, ?, ?::jsonb, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    push_enabled = EXCLUDED.push_enabled, industries = EXCLUDED.industries,
                    updated_at = EXCLUDED.updated_at
                """,
                subscription.userId(), subscription.pushEnabled(),
                toJson(subscription.industries()), offsetDateTimeOf(subscription.updatedAt()));

        syncStocks(subscription);
        return subscription;
    }

    @Override
    public List<Long> findUserIdsWithPushEnabled() {
        // 总开关默认开（#27/F16）：无订阅行 = 默认 push_enabled=true——LEFT JOIN app_user
        // 为底，仅显式关闭（s.push_enabled=FALSE）被排除；零行部署/绑定未存订阅
        // 的用户均在受众，不因无行静默失联。账号门（fix round 2）：受众须审批通过且
        // 未停用（status=APPROVED ∧ enabled）——PENDING 不旁路审批门收 DM、
        // REJECTED/停用用户不续收
        return jdbc.queryForList(
                """
                        SELECT u.id FROM app_user u
                        LEFT JOIN intelligence_subscription s ON s.user_id = u.id
                        WHERE u.status = 'APPROVED' AND u.enabled = TRUE
                          AND (s.user_id IS NULL OR s.push_enabled = TRUE)
                        ORDER BY u.id
                        """,
                Long.class);
    }

    @Override
    public List<IntelligenceSubscription> findAllWithStock(String stockCode) {
        List<MainRow> rows = jdbc.query(SELECT_MAIN + """
                 s WHERE s.push_enabled
                   AND s.user_id IN (SELECT user_id FROM intelligence_subscription_stock WHERE stock_code = ?)
                ORDER BY s.user_id
                """,
                (rs, i) -> mainRow(rs), stockCode);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, LinkedHashSet<SubscriptionStock>> stocksByUser =
                stocksOf(rows.stream().map(row -> row.userId).toList());
        return rows.stream().map(row -> assemble(row, stocksByUser.get(row.userId))).toList();
    }

    // ── 私有助手 ─────────────────────────────────────────────────

    /** 子表差集同步：删除不在新集合的标的，其余逐行 upsert（保留 added_at，改名走更新）。 */
    private void syncStocks(IntelligenceSubscription subscription) {
        Long userId = subscription.userId();
        List<String> codes = subscription.stocks().stream().map(SubscriptionStock::stockCode).toList();
        if (codes.isEmpty()) {
            jdbc.update("DELETE FROM intelligence_subscription_stock WHERE user_id = ?", userId);
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.addAll(codes);
        jdbc.update("DELETE FROM intelligence_subscription_stock WHERE user_id = ?"
                + " AND stock_code NOT IN (" + placeholders + ")", args.toArray());
        for (SubscriptionStock stock : subscription.stocks()) {
            jdbc.update("""
                    INSERT INTO intelligence_subscription_stock (user_id, stock_code, stock_name)
                    VALUES (?, ?, ?)
                    ON CONFLICT (user_id, stock_code) DO UPDATE SET stock_name = EXCLUDED.stock_name
                    """,
                    userId, stock.stockCode(), stock.stockName());
        }
    }

    /** 一次性取多用户的全部标的（findAllWithStock 装配聚合用）。 */
    private Map<Long, LinkedHashSet<SubscriptionStock>> stocksOf(List<Long> userIds) {
        String placeholders = String.join(",", Collections.nCopies(userIds.size(), "?"));
        Map<Long, LinkedHashSet<SubscriptionStock>> byUser = new LinkedHashMap<>();
        // RowCallbackHandler 每行回调一次（ResultSet 已定位当前行），不得再 rs.next() 推进；
        // 显式类型消解 query(String,RowCallbackHandler,Object...) 与 ResultSetExtractor 重载歧义
        RowCallbackHandler rowHandler = rs ->
                byUser.computeIfAbsent(rs.getLong("user_id"), k -> new LinkedHashSet<>())
                        .add(new SubscriptionStock(rs.getString("stock_code"),
                                rs.getString("stock_name")));
        jdbc.query("SELECT user_id, stock_code, stock_name FROM intelligence_subscription_stock"
                        + " WHERE user_id IN (" + placeholders + ") ORDER BY user_id, stock_code",
                rowHandler, userIds.toArray());
        return byUser;
    }

    private IntelligenceSubscription assemble(MainRow row, LinkedHashSet<SubscriptionStock> stocks) {
        return IntelligenceSubscription.reconstitute(row.userId, row.pushEnabled,
                row.industries, stocks == null ? new LinkedHashSet<>() : stocks, row.updatedAt);
    }

    private static MainRow mainRow(ResultSet rs) throws SQLException {
        return new MainRow(rs.getLong("user_id"), rs.getBoolean("push_enabled"),
                stringSet(rs, "industries"), instant(rs, "updated_at"));
    }

    private record MainRow(Long userId, boolean pushEnabled, LinkedHashSet<String> industries,
                           Instant updatedAt) {
    }

    private static OffsetDateTime offsetDateTimeOf(Instant instant) {
        return instant == null ? OffsetDateTime.now(ZoneOffset.UTC) : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** JSONB 列读为字符串集合（null 列归一为空集——缺省行 industries 有 DEFAULT，防御兜底）。 */
    private static LinkedHashSet<String> stringSet(ResultSet rs, String column) throws SQLException {
        String json = rs.getString(column);
        if (json == null) {
            return new LinkedHashSet<>();
        }
        try {
            return new LinkedHashSet<>(List.of(JSON.readValue(json, String[].class)));
        } catch (Exception e) {
            // 契约列由本仓库独占写入，解析失败即数据异常——显式抛出而非静默吞
            throw new SQLException("JSONB 列解析失败: " + column + " = " + json, e);
        }
    }

    private static String toJson(Set<String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 参数序列化失败", e);
        }
    }
}

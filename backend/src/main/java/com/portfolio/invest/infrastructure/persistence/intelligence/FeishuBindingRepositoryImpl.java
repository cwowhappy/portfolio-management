package com.portfolio.invest.infrastructure.persistence.intelligence;

import com.portfolio.invest.domain.intelligence.FeishuBindingRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 飞书绑定仓库实现（intelligence_feishu_binding）。单条 SELECT 即可表达，走
 * {@link JdbcTemplate} 原生 SQL，照 {@link BindingCodeRepositoryImpl} 先例
 * （同族绑定表、PG 方言语句 JPQL 无增益，不建 JPA 门面）。事务边界在 application 层。
 */
@Repository
public class FeishuBindingRepositoryImpl implements FeishuBindingRepository {

    private final JdbcTemplate jdbc;

    public FeishuBindingRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> findOpenIdByUserId(Long userId) {
        List<String> found = jdbc.query(
                "SELECT open_id FROM intelligence_feishu_binding WHERE user_id = ?",
                (rs, i) -> rs.getString("open_id"), userId);
        return found.stream().findFirst();
    }

    @Override
    public Optional<Instant> findBoundAtByUserId(Long userId) {
        List<Instant> found = jdbc.query(
                "SELECT bound_at FROM intelligence_feishu_binding WHERE user_id = ?",
                // TIMESTAMPTZ 显式时区读取（B9-⑥）：getTimestamp 按 JVM 默认时区折算墙钟，
                // getObject(OffsetDateTime) 带 offset 语义直达绝对 instant——与写入侧
                // atOffset(UTC) 对偶，读出值不变（DST 边界往返见 FeishuBindingRepositoryTest）
                (rs, i) -> rs.getObject("bound_at", OffsetDateTime.class).toInstant(), userId);
        return found.stream().findFirst();
    }

    @Override
    public boolean deleteByUserId(Long userId) {
        return jdbc.update("DELETE FROM intelligence_feishu_binding WHERE user_id = ?",
                userId) == 1;
    }

    @Override
    public Optional<Long> findUserIdByOpenId(String openId) {
        List<Long> found = jdbc.query(
                "SELECT user_id FROM intelligence_feishu_binding WHERE open_id = ?",
                (rs, i) -> rs.getLong("user_id"), openId);
        return found.stream().findFirst();
    }

    @Override
    public void upsert(Long userId, String openId, Instant boundAt) {
        // ON CONFLICT (user_id)：同用户重绑覆盖旧 open_id；open_id 撞他人行由
        // UNIQUE(open_id) 抛约束违例（Spring 译 DataIntegrityViolationException）
        jdbc.update("INSERT INTO intelligence_feishu_binding (user_id, open_id, bound_at)"
                        + " VALUES (?, ?, ?) ON CONFLICT (user_id) DO UPDATE"
                        + " SET open_id = EXCLUDED.open_id, bound_at = EXCLUDED.bound_at",
                userId, openId, boundAt.atOffset(ZoneOffset.UTC));
    }
}

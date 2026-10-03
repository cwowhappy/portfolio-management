package com.portfolio.invest.infrastructure.persistence.intelligence;

import com.portfolio.invest.domain.intelligence.BindingCodeRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 绑定码仓库实现（intelligence_binding_code）。单条 DELETE 即可表达，走
 * {@link JdbcTemplate} 原生 SQL，照 IntelligenceNewsRepositoryImpl 的先例
 * （PG 方言语句 JPQL 无增益，不建 JPA 门面）。事务边界在 application 层。
 */
@Repository
public class BindingCodeRepositoryImpl implements BindingCodeRepository {

    private final JdbcTemplate jdbc;

    public BindingCodeRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean trySave(String code, Long userId, Instant expiresAt) {
        // ON CONFLICT DO NOTHING：PK 撞码返回 0（false）而非抛约束违例——调用方换码重生成
        return jdbc.update(
                "INSERT INTO intelligence_binding_code (code, user_id, expires_at, used_at)"
                        + " VALUES (?, ?, ?, NULL) ON CONFLICT (code) DO NOTHING",
                code, userId, expiresAt.atOffset(ZoneOffset.UTC)) == 1;
    }

    @Override
    public int deleteExpiredBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM intelligence_binding_code WHERE expires_at < ?",
                cutoff.atOffset(ZoneOffset.UTC));
    }

    @Override
    public Optional<Long> findRedeemableUserId(String code, Instant now) {
        // 前置校验合并三拒绝态：不存在 / 已核销（used_at 非空）/ 已过期（expires_at < now）
        List<Long> found = jdbc.query(
                "SELECT user_id FROM intelligence_binding_code"
                        + " WHERE code = ? AND used_at IS NULL AND expires_at >= ?",
                (rs, i) -> rs.getLong("user_id"), code, now.atOffset(ZoneOffset.UTC));
        return found.stream().findFirst();
    }

    @Override
    public boolean tryMarkUsed(String code, Instant now) {
        // 受影响行数判定一次性语义：并发双核销只有一方置位（PG 行锁 + 谓词重评）
        return jdbc.update("UPDATE intelligence_binding_code SET used_at = ?"
                        + " WHERE code = ? AND used_at IS NULL AND expires_at >= ?",
                now.atOffset(ZoneOffset.UTC), code, now.atOffset(ZoneOffset.UTC)) == 1;
    }
}

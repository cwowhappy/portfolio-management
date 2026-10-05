package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.user.VerificationCode;
import com.portfolio.invest.domain.user.VerificationCodeRepository;
import com.portfolio.invest.domain.user.VerificationPurpose;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VerificationCodeRepositoryImpl implements VerificationCodeRepository {

    private final VerificationCodeJpaRepository jpa;
    private final JdbcTemplate jdbc;

    public VerificationCodeRepositoryImpl(VerificationCodeJpaRepository jpa, JdbcTemplate jdbc) {
        this.jpa = jpa;
        this.jdbc = jdbc;
    }

    @Override
    public VerificationCode save(VerificationCode code) {
        // saveAndFlush：让约束违例在事务内尽早抛出，同 UserRepositoryImpl 口径
        VerificationCodeJpaEntity entity = jpa.saveAndFlush(VerificationCodeJpaEntity.fromDomain(code));
        return entity.toDomain();
    }

    @Override
    public Optional<VerificationCode> findTopByEmailAndPurposeOrderByCreatedAtDesc(
            String email, VerificationPurpose purpose) {
        return jpa.findTopByEmailAndPurposeOrderByCreatedAtDesc(email, purpose).map(VerificationCodeJpaEntity::toDomain);
    }

    @Override
    public long countByEmailAndCreatedAtAfter(String email, Instant since) {
        return jpa.countByEmailAndCreatedAtAfter(email, since);
    }

    @Override
    public boolean tryMarkUsed(Long id, Instant now) {
        // 受影响行数判定一次性语义：并发双验只有一方置位（PG 行锁 + 谓词重评），
        // 消除 verify 读-判定-写三步竞态（B8）；照 BindingCodeRepositoryImpl 先例
        return jdbc.update("UPDATE verification_code SET used_at = ?"
                        + " WHERE id = ? AND used_at IS NULL AND expires_at >= ?",
                now.atOffset(ZoneOffset.UTC), id, now.atOffset(ZoneOffset.UTC)) == 1;
    }
}

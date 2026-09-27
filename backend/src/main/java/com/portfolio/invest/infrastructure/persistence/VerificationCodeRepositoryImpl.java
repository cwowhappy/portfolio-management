package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.user.VerificationCode;
import com.portfolio.invest.domain.user.VerificationCodeRepository;
import com.portfolio.invest.domain.user.VerificationPurpose;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class VerificationCodeRepositoryImpl implements VerificationCodeRepository {

    private final VerificationCodeJpaRepository jpa;

    public VerificationCodeRepositoryImpl(VerificationCodeJpaRepository jpa) {
        this.jpa = jpa;
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
}

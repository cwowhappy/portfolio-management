package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.user.VerificationPurpose;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationCodeJpaRepository extends JpaRepository<VerificationCodeJpaEntity, Long> {
    Optional<VerificationCodeJpaEntity> findTopByEmailAndPurposeOrderByCreatedAtDesc(
            String email, VerificationPurpose purpose);
    long countByEmailAndCreatedAtAfter(String email, Instant since);
}

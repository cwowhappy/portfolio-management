package com.portfolio.invest.domain.user;

import java.time.Instant;
import java.util.Optional;

/** 验证码仓库端口：实现见 infrastructure.persistence。 */
public interface VerificationCodeRepository {
    VerificationCode save(VerificationCode code);
    Optional<VerificationCode> findTopByEmailAndPurposeOrderByCreatedAtDesc(String email, VerificationPurpose purpose);
    long countByEmailAndCreatedAtAfter(String email, Instant since);
}

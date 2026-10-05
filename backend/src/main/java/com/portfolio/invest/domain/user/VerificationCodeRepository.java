package com.portfolio.invest.domain.user;

import java.time.Instant;
import java.util.Optional;

/** 验证码仓库端口：实现见 infrastructure.persistence。 */
public interface VerificationCodeRepository {
    VerificationCode save(VerificationCode code);
    Optional<VerificationCode> findTopByEmailAndPurposeOrderByCreatedAtDesc(String email, VerificationPurpose purpose);
    long countByEmailAndCreatedAtAfter(String email, Instant since);

    /**
     * 原子消费：未用未过期才置 used_at（受影响行数判定一次性语义），
     * 并发同码双验恰一成功——读-判定-写三步在应用层无法保证（B8）。
     */
    boolean tryMarkUsed(Long id, Instant now);
}

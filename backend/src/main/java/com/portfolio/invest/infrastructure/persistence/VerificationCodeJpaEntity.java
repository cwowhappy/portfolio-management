package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.user.VerificationCode;
import com.portfolio.invest.domain.user.VerificationPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** verification_code 表的 JPA 映射（领域 VerificationCode 为纯 POJO，见 domain/user）。 */
@Entity
@Table(name = "verification_code")
public class VerificationCodeJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 254)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationPurpose purpose;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected VerificationCodeJpaEntity() {}

    static VerificationCodeJpaEntity fromDomain(VerificationCode c) {
        VerificationCodeJpaEntity e = new VerificationCodeJpaEntity();
        e.id = c.id();
        e.email = c.email();
        e.purpose = c.purpose();
        e.codeHash = c.codeHash();
        e.attempts = c.attempts();
        e.usedAt = c.usedAt();
        e.expiresAt = c.expiresAt();
        e.createdAt = c.createdAt();
        return e;
    }

    VerificationCode toDomain() {
        return VerificationCode.reconstitute(id, email, purpose, codeHash, attempts, usedAt, expiresAt, createdAt);
    }
}

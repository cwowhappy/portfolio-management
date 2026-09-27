package com.portfolio.invest.domain.user;

import java.time.Instant;

/** 邮箱验证码聚合：哈希存储（明文码由 application 生成后即弃），不可变实体、转移返回新实例。 */
public final class VerificationCode {

    public static final int MAX_ATTEMPTS = 5;

    private final Long id;
    private final String email;
    private final VerificationPurpose purpose;
    private final String codeHash;
    private final int attempts;
    private final Instant usedAt;
    private final Instant expiresAt;
    private final Instant createdAt;

    private VerificationCode(Long id, String email, VerificationPurpose purpose, String codeHash,
                             int attempts, Instant usedAt, Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.purpose = purpose;
        this.codeHash = codeHash;
        this.attempts = attempts;
        this.usedAt = usedAt;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    /** 从持久化还原 / application 组装新码共用（新码 id=null、usedAt=null、attempts=0）。 */
    public static VerificationCode reconstitute(Long id, String email, VerificationPurpose purpose,
                                                String codeHash, int attempts, Instant usedAt,
                                                Instant expiresAt, Instant createdAt) {
        return new VerificationCode(id, email, purpose, codeHash, attempts, usedAt, expiresAt, createdAt);
    }

    public VerificationCode attemptFailed() {
        return new VerificationCode(id, email, purpose, codeHash, attempts + 1, usedAt, expiresAt, createdAt);
    }

    public VerificationCode markUsed(Instant now) {
        return new VerificationCode(id, email, purpose, codeHash, attempts, now, expiresAt, createdAt);
    }

    public boolean isUsed() { return usedAt != null; }
    public boolean isExpired(Instant now) { return now.isAfter(expiresAt); }
    public boolean attemptsExhausted() { return attempts >= MAX_ATTEMPTS; }

    public Long id() { return id; }
    public String email() { return email; }
    public VerificationPurpose purpose() { return purpose; }
    public String codeHash() { return codeHash; }
    public int attempts() { return attempts; }
    public Instant usedAt() { return usedAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant createdAt() { return createdAt; }
}

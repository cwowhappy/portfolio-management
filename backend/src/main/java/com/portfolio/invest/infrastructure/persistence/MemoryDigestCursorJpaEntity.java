package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.memory.MemoryDigestCursor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** memory_digest_cursor JPA 实体：全局单行（id 恒 1，由 V7 CHECK 锁死），无自增。 */
@Entity
@Table(name = "memory_digest_cursor")
public class MemoryDigestCursorJpaEntity {

    /** 全局单行主键（设计规格 §4.1：id=1 CHECK，seed 迁移即插入）。 */
    public static final int SINGLE_ROW_ID = 1;

    @Id
    private Integer id;
    @Column(name = "last_success_at", nullable = false)
    private Instant lastSuccessAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MemoryDigestCursorJpaEntity() {}

    public static MemoryDigestCursorJpaEntity fromDomain(MemoryDigestCursor c) {
        MemoryDigestCursorJpaEntity e = new MemoryDigestCursorJpaEntity();
        e.id = SINGLE_ROW_ID;
        e.lastSuccessAt = c.lastSuccessAt();
        e.updatedAt = Instant.now();
        return e;
    }

    public MemoryDigestCursor toDomain() {
        return new MemoryDigestCursor(lastSuccessAt);
    }
}

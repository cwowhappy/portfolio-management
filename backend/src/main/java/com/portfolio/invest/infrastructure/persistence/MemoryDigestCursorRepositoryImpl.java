package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.memory.MemoryDigestCursor;
import com.portfolio.invest.domain.memory.MemoryDigestCursorRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 摘要游标仓库实现：固定全局单行 id=1（行由 V7 seed 保证存在，save 恒覆盖）。 */
@Repository
public class MemoryDigestCursorRepositoryImpl implements MemoryDigestCursorRepository {
    private final MemoryDigestCursorJpaRepository jpa;

    public MemoryDigestCursorRepositoryImpl(MemoryDigestCursorJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override public Optional<MemoryDigestCursor> find() {
        return jpa.findById(MemoryDigestCursorJpaEntity.SINGLE_ROW_ID)
                .map(MemoryDigestCursorJpaEntity::toDomain);
    }

    @Override public MemoryDigestCursor save(MemoryDigestCursor cursor) {
        return jpa.save(MemoryDigestCursorJpaEntity.fromDomain(cursor)).toDomain();
    }
}

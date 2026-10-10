package com.portfolio.invest.domain.memory;

import java.util.Optional;

/** 摘要游标仓库端口（memory_digest_cursor，实现固定全局单行 id=1）。 */
public interface MemoryDigestCursorRepository {

    Optional<MemoryDigestCursor> find();

    MemoryDigestCursor save(MemoryDigestCursor cursor);
}

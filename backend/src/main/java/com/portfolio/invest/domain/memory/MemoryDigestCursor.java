package com.portfolio.invest.domain.memory;

import java.time.Instant;

/**
 * 摘要调度游标（MS-31 设计规格 §4.1，memory_digest_cursor 全局单行 id=1）：
 * 上次成功批次的完成时刻 = 下次窗口起点；仅成功处理全部用户后推进（决议 #8）。
 */
public record MemoryDigestCursor(Instant lastSuccessAt) {

    /** 推进游标到给定时刻（不可变，返回新实例）。 */
    public MemoryDigestCursor advanceTo(Instant lastSuccessAt) {
        return new MemoryDigestCursor(lastSuccessAt);
    }
}

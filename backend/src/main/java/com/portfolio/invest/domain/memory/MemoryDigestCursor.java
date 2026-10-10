package com.portfolio.invest.domain.memory;

import java.time.Instant;

/**
 * 摘要调度游标（MS-31 设计规格 §4.1，memory_digest_cursor 全局单行 id=1）：
 * 上次批次的批时刻 = 下次窗口起点；批执行完成即推进（B6 裁决——仅全批顶层异常与
 * 环境前置门不推进；单用户失败跳过不重试该窗口，损失该用户当日摘要）。
 */
public record MemoryDigestCursor(Instant lastSuccessAt) {

    /** 推进游标到给定时刻（不可变，返回新实例）。 */
    public MemoryDigestCursor advanceTo(Instant lastSuccessAt) {
        return new MemoryDigestCursor(lastSuccessAt);
    }
}

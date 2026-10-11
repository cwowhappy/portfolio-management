package com.portfolio.invest.application.memory;

/** 日账本节类型：节头前缀判定——「## Memory Flush —」→ FLUSH；「## 外部事件摘要」→ EXTERNAL_EVENT；其余兜底 FLUSH 只读展示。 */
public enum SectionKind {

    /** harness 库记忆冲刷节（consolidation 的日账本源头）。 */
    FLUSH,

    /** 外部事件摘要节（F03 摘要链写入，决议 #9）。 */
    EXTERNAL_EVENT
}

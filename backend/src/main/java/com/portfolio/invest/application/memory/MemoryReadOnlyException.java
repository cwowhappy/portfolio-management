package com.portfolio.invest.application.memory;

/**
 * 记忆只读目标（设计规格 §2.1/§2.3）：删除/编辑请求命中「# 」标题行——库写策展层标题是
 * 结构锚点，不可经管理链改写。web 层映射 400。
 */
public class MemoryReadOnlyException extends RuntimeException {

    public MemoryReadOnlyException(String message) {
        super(message);
    }
}

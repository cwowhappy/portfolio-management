package com.portfolio.invest.application.memory;

/**
 * 记忆行/节漂移（设计规格 §2.2/§2.3）：baseHash 相符但行寻址失效——lineIndex 越界、
 * 行内容与 expectLine 不符、或节头不再存在（库 consolidation 重排等）。web 层映射 410。
 */
public class MemoryStaleException extends RuntimeException {

    public MemoryStaleException(String message) {
        super(message);
    }
}

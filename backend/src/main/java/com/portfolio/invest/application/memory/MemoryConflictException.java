package com.portfolio.invest.application.memory;

/**
 * 记忆文件乐观并发冲突（设计规格 §2.2/§2.3）：写入前重读重算的整文件 hash 与 baseHash 不符——
 * 文件在读取后被修改（harness 库写入或其他会话变更）。web 层映射 409（提示刷新重试）。
 */
public class MemoryConflictException extends RuntimeException {

    public MemoryConflictException(String message) {
        super(message);
    }
}

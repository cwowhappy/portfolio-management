package com.portfolio.invest.domain.conversation;

/**
 * 会话乐观校验冲突（B6）：PUT 消息携带的 If-Match 与服务端 updated_at 不符（其他写端先改）。
 * 独立异常类型，接入层映射 409；不混入 ConversationException 的错误码体系（无业务码可枚举）。
 */
public class ConversationConflictException extends RuntimeException {

    public ConversationConflictException(String message) {
        super(message);
    }
}

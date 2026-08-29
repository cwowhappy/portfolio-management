package com.portfolio.invest.domain.conversation;

/** 会话域错误码（B2）：抛点、GlobalExceptionHandler、测试同引此处常量，不上 enum（switch 按字符串）。 */
public final class ConversationErrorCode {

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String INVALID_ID = "INVALID_ID";
    public static final String INVALID_MESSAGE = "INVALID_MESSAGE";

    private ConversationErrorCode() {}
}

package com.portfolio.invest.domain.conversation;

/** 会话域异常：接入层映射 HTTP（NOT_FOUND→404、INVALID_ID→400）。 */
public class ConversationException extends RuntimeException {
    private final String code;
    public ConversationException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String getCode() { return code; }
}

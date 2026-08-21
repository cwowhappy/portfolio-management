package com.portfolio.invest.domain.user;

/** 用户域异常：code + message，由接入层映射 HTTP 状态。 */
public class UserException extends RuntimeException {
    private final String code;
    public UserException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String getCode() { return code; }
}

package com.portfolio.invest.domain.market;

/** 行情数据获取失败（源不可用/限流/超时等），由服务层捕获并降级或转换为结构化错误。 */
public class MarketDataException extends RuntimeException {

    private final String code;

    public MarketDataException(String code, String message) {
        super(message);
        this.code = code;
    }

    public MarketDataException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

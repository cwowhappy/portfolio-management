package com.portfolio.invest.domain.analytics;

public class AnalyticsException extends RuntimeException {
    private final String code;

    public AnalyticsException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}

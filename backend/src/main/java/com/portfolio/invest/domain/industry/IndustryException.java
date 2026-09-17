package com.portfolio.invest.domain.industry;

public class IndustryException extends RuntimeException {
    private final String code;

    public IndustryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

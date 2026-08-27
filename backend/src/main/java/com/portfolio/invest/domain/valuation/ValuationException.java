package com.portfolio.invest.domain.valuation;

public class ValuationException extends RuntimeException {
    private final String code;

    public ValuationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

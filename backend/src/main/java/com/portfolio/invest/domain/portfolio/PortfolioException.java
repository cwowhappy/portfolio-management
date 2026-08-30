package com.portfolio.invest.domain.portfolio;

public class PortfolioException extends RuntimeException {
    private final String code;

    public PortfolioException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

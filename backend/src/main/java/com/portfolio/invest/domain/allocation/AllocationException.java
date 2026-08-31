package com.portfolio.invest.domain.allocation;

public class AllocationException extends RuntimeException {
    private final String code;

    public AllocationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}

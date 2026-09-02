package com.portfolio.invest.domain.journal;

public class JournalException extends RuntimeException {
    private final String code;

    public JournalException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}

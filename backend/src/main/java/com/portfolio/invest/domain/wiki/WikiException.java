package com.portfolio.invest.domain.wiki;

public class WikiException extends RuntimeException {
    private final String code;

    public WikiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}

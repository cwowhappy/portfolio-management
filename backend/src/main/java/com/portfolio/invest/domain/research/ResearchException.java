package com.portfolio.invest.domain.research;

/** research 域异常（照 {@code JournalException} 先例）。 */
public class ResearchException extends RuntimeException {
    private final String code;

    public ResearchException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}

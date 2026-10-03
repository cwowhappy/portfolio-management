package com.portfolio.invest.domain.intelligence;

/** intelligence 域异常（照 {@code ResearchException} 先例）。 */
public class IntelligenceException extends RuntimeException {
    private final String code;

    public IntelligenceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}

package com.portfolio.invest.domain.eval;

/** eval 域异常（照 {@code IntelligenceException} 先例，code 见 {@link EvalErrorCode}）。 */
public class EvalException extends RuntimeException {
    private final String code;

    public EvalException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

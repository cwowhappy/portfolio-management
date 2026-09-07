package com.portfolio.invest.domain.skill;

public class SkillException extends RuntimeException {
    private final String code;
    public SkillException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}

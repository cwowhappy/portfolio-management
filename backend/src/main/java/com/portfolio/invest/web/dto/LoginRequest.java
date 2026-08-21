package com.portfolio.invest.web.dto;

public record LoginRequest(String username, String password, Boolean rememberMe) {
    public Boolean rememberMe() {
        return Boolean.TRUE.equals(rememberMe);
    }
}

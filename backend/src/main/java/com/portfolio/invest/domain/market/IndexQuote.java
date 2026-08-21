package com.portfolio.invest.domain.market;

/** 指数行情。 */
public record IndexQuote(String code, String name, double price, double change, double changePct) {}

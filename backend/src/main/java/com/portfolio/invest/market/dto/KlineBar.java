package com.portfolio.invest.market.dto;

/** 单根 K 线。 */
public record KlineBar(
        String date,
        double open,
        double close,
        double high,
        double low,
        long volume,
        double amount,
        double amplitudePct) {}

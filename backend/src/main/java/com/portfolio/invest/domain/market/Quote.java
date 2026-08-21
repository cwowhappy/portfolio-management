package com.portfolio.invest.domain.market;

/** 实时行情快照。 */
public record Quote(
        String code,
        String name,
        double price,
        double change,
        double changePct,
        double open,
        double high,
        double low,
        double prevClose,
        long volume,
        double amount,
        Double pe,
        Double pb,
        String time) {}

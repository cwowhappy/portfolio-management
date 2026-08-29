package com.portfolio.invest.application.portfolio;

import com.portfolio.invest.domain.portfolio.CashTransaction;
import com.portfolio.invest.domain.portfolio.CashTransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CashTransactionView(Long id, Long groupId, CashTransactionType type,
                                  BigDecimal amount, LocalDate txDate, String note) {
    public static CashTransactionView from(CashTransaction t) {
        return new CashTransactionView(t.id(), t.groupId(), t.type(), t.amount(), t.txDate(), t.note());
    }
}

package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.portfolio.CashTransaction;
import com.portfolio.invest.domain.portfolio.CashTransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "cash_transaction")
public class CashTransactionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CashTransactionType type;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "tx_date", nullable = false)
    private LocalDate txDate;

    @Column(name = "note")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CashTransactionJpaEntity() {}

    public static CashTransactionJpaEntity fromDomain(CashTransaction tx) {
        CashTransactionJpaEntity e = new CashTransactionJpaEntity();
        e.id = tx.id();
        e.groupId = tx.groupId();
        e.type = tx.type();
        e.amount = tx.amount();
        e.txDate = tx.txDate();
        e.note = tx.note();
        e.createdAt = tx.createdAt();
        return e;
    }

    public CashTransaction toDomain() {
        return new CashTransaction(id, groupId, type, amount, txDate, note, createdAt);
    }
}

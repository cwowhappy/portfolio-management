package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.journal.PeriodType;
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
@Table(name = "journal_entry")
public class JournalEntryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private JournalEntryType type;

    @Column(name = "stock_code", length = 16)
    private String stockCode;

    @Column(name = "stock_name", length = 64)
    private String stockName;

    @Column(name = "trade_id")
    private Long tradeId;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "target_price")
    private BigDecimal targetPrice;

    @Column(name = "stop_loss")
    private BigDecimal stopLoss;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", length = 16)
    private PeriodType periodType;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected JournalEntryJpaEntity() {}

    public static JournalEntryJpaEntity fromDomain(JournalEntry e) {
        JournalEntryJpaEntity entity = new JournalEntryJpaEntity();
        entity.id = e.id();
        entity.userId = e.userId();
        entity.type = e.type();
        entity.stockCode = e.stockCode();
        entity.stockName = e.stockName();
        entity.tradeId = e.tradeId();
        entity.title = e.title();
        entity.content = e.content();
        entity.targetPrice = e.targetPrice();
        entity.stopLoss = e.stopLoss();
        entity.periodType = e.periodType();
        entity.periodStart = e.periodStart();
        entity.periodEnd = e.periodEnd();
        entity.eventDate = e.eventDate();
        entity.createdAt = e.createdAt();
        entity.updatedAt = e.updatedAt();
        return entity;
    }

    public JournalEntry toDomain() {
        return JournalEntry.reconstitute(id, userId, type, stockCode, stockName, tradeId, title, content,
                targetPrice, stopLoss, periodType, periodStart, periodEnd, eventDate, createdAt, updatedAt);
    }
}

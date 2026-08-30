package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.portfolio.Trade;
import com.portfolio.invest.domain.portfolio.TradeType;
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
@Table(name = "trade")
public class TradeJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "position_id", nullable = false)
    private Long positionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private TradeType type;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(nullable = false)
    private BigDecimal fee;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TradeJpaEntity() {}

    public static TradeJpaEntity fromDomain(Trade t) {
        TradeJpaEntity e = new TradeJpaEntity();
        e.id = t.id();
        e.positionId = t.positionId();
        e.type = t.type();
        e.tradeDate = t.tradeDate();
        e.price = t.price();
        e.quantity = t.quantity();
        e.fee = t.fee();
        e.createdAt = t.createdAt();
        return e;
    }

    public Trade toDomain() {
        return new Trade(id, positionId, type, tradeDate, price, quantity, fee, createdAt);
    }
}

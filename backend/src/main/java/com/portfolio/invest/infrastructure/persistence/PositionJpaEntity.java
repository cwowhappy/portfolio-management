package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.portfolio.Position;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "position")
public class PositionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "portfolio_id", nullable = false)
    private Long portfolioId;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "stock_code", nullable = false, length = 16)
    private String stockCode;

    @Column(name = "stock_name", nullable = false, length = 64)
    private String stockName;

    @Column(name = "quantity", nullable = false)
    private BigDecimal quantity;

    @Column(name = "cost_basis", nullable = false)
    private BigDecimal costBasis;

    @Column(name = "total_buy_cost", nullable = false)
    private BigDecimal totalBuyCost;

    @Column(name = "cumulative_cash_dividend", nullable = false)
    private BigDecimal cumulativeCashDividend;

    @Column(name = "realized_pnl", nullable = false)
    private BigDecimal realizedPnl;

    @Column(name = "net_cash_flow", nullable = false)
    private BigDecimal netCashFlow;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected PositionJpaEntity() {}

    public static PositionJpaEntity fromDomain(Position p) {
        PositionJpaEntity e = new PositionJpaEntity();
        e.id = p.id();
        e.portfolioId = p.portfolioId();
        e.groupId = p.groupId();
        e.stockCode = p.stockCode();
        e.stockName = p.stockName();
        e.quantity = p.quantity();
        e.costBasis = p.costBasis();
        e.totalBuyCost = p.totalBuyCost();
        e.cumulativeCashDividend = p.cumulativeCashDividend();
        e.realizedPnl = p.realizedPnl();
        e.netCashFlow = p.netCashFlow();
        e.createdAt = p.createdAt();
        e.updatedAt = p.updatedAt();
        e.version = p.version();
        return e;
    }

    public Position toDomain() {
        return Position.reconstitute(id, portfolioId, groupId, stockCode, stockName,
                quantity, costBasis, totalBuyCost, cumulativeCashDividend, realizedPnl, netCashFlow,
                createdAt, updatedAt, version);
    }
}

package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.portfolio.CostMethod;
import com.portfolio.invest.domain.portfolio.Portfolio;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "portfolio")
public class PortfolioJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_method", nullable = false, length = 16)
    private CostMethod costMethod;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PortfolioJpaEntity() {}

    public Portfolio toDomain() {
        return Portfolio.reconstitute(id, userId, costMethod, createdAt, updatedAt);
    }
}

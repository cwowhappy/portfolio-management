package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashTransactionJpaRepository extends JpaRepository<CashTransactionJpaEntity, Long> {
    List<CashTransactionJpaEntity> findByGroupIdOrderByIdAsc(Long groupId);
}

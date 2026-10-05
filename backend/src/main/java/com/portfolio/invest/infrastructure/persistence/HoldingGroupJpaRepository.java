package com.portfolio.invest.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldingGroupJpaRepository extends JpaRepository<HoldingGroupJpaEntity, Long> {

    List<HoldingGroupJpaEntity> findByPortfolioIdOrderByIdAsc(Long portfolioId);

    Optional<HoldingGroupJpaEntity> findByIdAndPortfolioId(Long id, Long portfolioId);

    /**
     * 组行悲观锁（SELECT ... FOR UPDATE）：现金写路径（buy/现金转出）先锁组行再读算现金，
     * 串行化同组并发写，消除「读算现金→写」窗口内双双透支的 TOCTOU。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from HoldingGroupJpaEntity g where g.id = :id and g.portfolioId = :pid")
    Optional<HoldingGroupJpaEntity> findByIdAndPortfolioIdForUpdate(@Param("id") Long id, @Param("pid") Long portfolioId);
}

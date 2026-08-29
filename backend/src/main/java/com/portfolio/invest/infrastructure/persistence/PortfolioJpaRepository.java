package com.portfolio.invest.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PortfolioJpaRepository extends JpaRepository<PortfolioJpaEntity, Long> {
    Optional<PortfolioJpaEntity> findByUserId(Long userId);

    /**
     * 幂等创建组合：并发首次访问时，唯一一次 INSERT 生效，其余命中 {@code ON CONFLICT} 后静默跳过。
     * 该查询为 {@link Modifying}，要求调用方处于活动事务中（事务边界见 application 层）。
     */
    @Modifying
    @Query(value = "INSERT INTO portfolio (user_id, cost_method, created_at, updated_at) "
            + "VALUES (:userId, 'WEIGHTED_AVG', now(), now()) ON CONFLICT (user_id) DO NOTHING",
            nativeQuery = true)
    int insertPortfolioIfAbsent(@Param("userId") Long userId);
}

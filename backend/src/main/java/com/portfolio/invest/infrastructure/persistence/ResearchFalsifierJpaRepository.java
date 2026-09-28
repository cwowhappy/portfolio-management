package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResearchFalsifierJpaRepository extends JpaRepository<ResearchFalsifierJpaEntity, Long> {

    List<ResearchFalsifierJpaEntity> findByStrategyIdOrderByIdAsc(Long strategyId);

    /** 整替第一步（照 AllocationPlanWeightJpaRepository 先例）：批量直删，执行前自动 flush 待插行。 */
    @Modifying
    @Query("DELETE FROM ResearchFalsifierJpaEntity f WHERE f.strategyId = :strategyId")
    void deleteByStrategyId(@Param("strategyId") Long strategyId);
}

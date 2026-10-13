package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResearchEntryBatchJpaRepository extends JpaRepository<ResearchEntryBatchJpaEntity, Long> {

    List<ResearchEntryBatchJpaEntity> findByPlanIdOrderBySeqAsc(Long planId);

    /**
     * 整替第一步（batches 先删，避免 FK 悬挂；照 ResearchFalsifierJpaRepository 先例）。
     * 不加 clearAutomatically（2026-10-12 B5 复核）：与 plan 整替同事务——删旧批插新批
     * 不同行，无该表实体被批量语句影响后复用。
     */
    @Modifying
    @Query("DELETE FROM ResearchEntryBatchJpaEntity b WHERE b.planId = :planId")
    void deleteByPlanId(@Param("planId") Long planId);
}

package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResearchEntryBatchJpaRepository extends JpaRepository<ResearchEntryBatchJpaEntity, Long> {

    List<ResearchEntryBatchJpaEntity> findByPlanIdOrderBySeqAsc(Long planId);

    /** 整替第一步（batches 先删，避免 FK 悬挂；照 ResearchFalsifierJpaRepository 先例）。 */
    @Modifying
    @Query("DELETE FROM ResearchEntryBatchJpaEntity b WHERE b.planId = :planId")
    void deleteByPlanId(@Param("planId") Long planId);
}

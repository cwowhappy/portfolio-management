package com.portfolio.invest.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResearchEntryPlanJpaRepository extends JpaRepository<ResearchEntryPlanJpaEntity, Long> {

    Optional<ResearchEntryPlanJpaEntity> findByProjectId(Long projectId);

    /** 整替第一步（照 ResearchFalsifierJpaRepository.deleteByStrategyId 先例）。 */
    @Modifying
    @Query("DELETE FROM ResearchEntryPlanJpaEntity p WHERE p.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}

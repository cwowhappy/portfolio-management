package com.portfolio.invest.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResearchEntryPlanJpaRepository extends JpaRepository<ResearchEntryPlanJpaEntity, Long> {

    Optional<ResearchEntryPlanJpaEntity> findByProjectId(Long projectId);

    /**
     * 整替第一步（照 ResearchFalsifierJpaRepository.deleteByStrategyId 先例）。
     * 不加 clearAutomatically（2026-10-12 B5 复核）：整替为「删旧行+插新行」不同行——同事务
     * 内被删行的托管实体未被修改（提交脏检查无 SQL），DELETE 后也无同表读回，无脏快照覆盖/
     * 陈旧读风险。
     */
    @Modifying
    @Query("DELETE FROM ResearchEntryPlanJpaEntity p WHERE p.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}

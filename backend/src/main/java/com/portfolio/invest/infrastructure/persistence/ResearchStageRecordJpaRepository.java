package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.ResearchStage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchStageRecordJpaRepository extends JpaRepository<ResearchStageRecordJpaEntity, Long> {
    Optional<ResearchStageRecordJpaEntity> findByProjectIdAndStage(Long projectId, ResearchStage stage);

    /** 只取有手动覆盖的行（manual_state IS NOT NULL），NULL 行不进完成度覆盖 Map（S6）。 */
    List<ResearchStageRecordJpaEntity> findByProjectIdAndManualStateIsNotNull(Long projectId);
}

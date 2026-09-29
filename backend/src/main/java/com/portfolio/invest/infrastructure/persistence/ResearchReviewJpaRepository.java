package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchReviewJpaRepository extends JpaRepository<ResearchReviewJpaEntity, Long> {

    /** periodStart 倒序 + id 倒序稳定序（照 idx_review_project 排序口径）。 */
    List<ResearchReviewJpaEntity> findByProjectIdOrderByPeriodStartDescIdDesc(Long projectId);

    Optional<ResearchReviewJpaEntity> findByIdAndProjectId(Long id, Long projectId);
}

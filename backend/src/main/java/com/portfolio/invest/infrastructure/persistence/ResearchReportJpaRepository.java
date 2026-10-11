package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchReportJpaRepository extends JpaRepository<ResearchReportJpaEntity, Long> {
    List<ResearchReportJpaEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}

package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.ProjectStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchProjectJpaRepository extends JpaRepository<ResearchProjectJpaEntity, Long> {
    List<ResearchProjectJpaEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);
    List<ResearchProjectJpaEntity> findByUserIdAndStatusOrderByUpdatedAtDesc(Long userId, ProjectStatus status);
}

package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** append-only 表：仓库侧仅 insert（save）+ 按项目倒序读（前端列表用）。 */
public interface ResearchFalsifierReviewJpaRepository extends JpaRepository<ResearchFalsifierReviewJpaEntity, Long> {

    List<ResearchFalsifierReviewJpaEntity> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId);
}

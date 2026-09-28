package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** append-only 表：仓库侧只读 + insert（save）；T5 日终扫描按 falsifier 去重（未评审行不重复 insert）。 */
public interface ResearchFalsifierHitJpaRepository extends JpaRepository<ResearchFalsifierHitJpaEntity, Long> {

    List<ResearchFalsifierHitJpaEntity> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId);

    List<ResearchFalsifierHitJpaEntity> findByProjectIdAndReviewIdIsNull(Long projectId);
}

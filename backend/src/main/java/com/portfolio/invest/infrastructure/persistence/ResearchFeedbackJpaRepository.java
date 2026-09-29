package com.portfolio.invest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** append-only 表（只收集不生效）：仓库侧仅 insert，无读/更新用例（端口 javadoc 交叉注明）。 */
public interface ResearchFeedbackJpaRepository extends JpaRepository<ResearchFeedbackJpaEntity, Long> {
}

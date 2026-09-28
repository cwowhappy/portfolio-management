package com.portfolio.invest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** append-only 表：仓库侧仅 insert（save）——读取用例（复盘/详情产物分区）到 P4 再开。 */
public interface ResearchCheckRecordJpaRepository extends JpaRepository<ResearchCheckRecordJpaEntity, Long> {
}

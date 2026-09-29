package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** append-only 表：仓库侧仅 insert（save）——读取用例自 P4-T3 开 findChecks（复盘纪律遵守度预填）。 */
public interface ResearchCheckRecordJpaRepository extends JpaRepository<ResearchCheckRecordJpaEntity, Long> {

    /** createdAt 倒序 + id 倒序稳定序（与 hit 表读取序同法）。 */
    List<ResearchCheckRecordJpaEntity> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId);
}

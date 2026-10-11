package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.ReportTask;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportTaskJpaRepository extends JpaRepository<ReportTaskJpaEntity, Long> {

    /** 项目最新任务（ORDER BY id DESC LIMIT 1）。 */
    Optional<ReportTaskJpaEntity> findFirstByProjectIdOrderByIdDesc(Long projectId);

    /** 启动恢复取数（§3.9）：活跃任务全量，id 升序保证恢复顺序确定。 */
    List<ReportTaskJpaEntity> findByStatusInOrderByIdAsc(List<ReportTask.Status> statuses);
}

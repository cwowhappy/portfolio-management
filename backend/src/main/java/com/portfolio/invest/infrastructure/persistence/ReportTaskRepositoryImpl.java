package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.ReportTask;
import com.portfolio.invest.domain.research.ReportTaskRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

// 事务边界在 application 层（P2）；本类不挂 @Transactional（照 ResearchProjectRepositoryImpl 先例）。
@Repository
public class ReportTaskRepositoryImpl implements ReportTaskRepository {

    private static final List<ReportTask.Status> ACTIVE_STATUSES =
            List.of(ReportTask.Status.PENDING, ReportTask.Status.RUNNING);

    private final ReportTaskJpaRepository taskJpa;

    public ReportTaskRepositoryImpl(ReportTaskJpaRepository taskJpa) {
        this.taskJpa = taskJpa;
    }

    @Override
    public ReportTask save(ReportTask task) {
        // saveAndFlush：uq_report_task_active 部分唯一索引违例（并发双发起第二笔）事务内尽早抛出
        return taskJpa.saveAndFlush(ReportTaskJpaEntity.fromDomain(task)).toDomain();
    }

    @Override
    public Optional<ReportTask> findById(Long id) {
        return taskJpa.findById(id).map(ReportTaskJpaEntity::toDomain);
    }

    @Override
    public Optional<ReportTask> findLatestByProjectId(Long projectId) {
        return taskJpa.findFirstByProjectIdOrderByIdDesc(projectId).map(ReportTaskJpaEntity::toDomain);
    }

    @Override
    public List<ReportTask> findActiveAll() {
        return taskJpa.findByStatusInOrderByIdAsc(ACTIVE_STATUSES).stream()
                .map(ReportTaskJpaEntity::toDomain).toList();
    }
}

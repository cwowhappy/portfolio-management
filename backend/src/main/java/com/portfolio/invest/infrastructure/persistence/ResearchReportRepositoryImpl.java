package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.ResearchReport;
import com.portfolio.invest.domain.research.ResearchReportRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

// 事务边界在 application 层（P2）；本类不挂 @Transactional（照 ResearchProjectRepositoryImpl 先例，
// confirmIfPending/rejectIfPending 的 @Modifying 原生更新由调用方的事务包裹）。
@Repository
public class ResearchReportRepositoryImpl implements ResearchReportRepository {

    private final ResearchReportJpaRepository reportJpa;

    public ResearchReportRepositoryImpl(ResearchReportJpaRepository reportJpa) {
        this.reportJpa = reportJpa;
    }

    @Override
    public ResearchReport save(ResearchReport report) {
        // saveAndFlush：约束违例事务内尽早抛出（区别于延迟到提交时冒泡成 500）
        return reportJpa.saveAndFlush(ResearchReportJpaEntity.fromDomain(report)).toDomain();
    }

    @Override
    public Optional<ResearchReport> findById(Long id) {
        return reportJpa.findById(id).map(ResearchReportJpaEntity::toDomain);
    }

    @Override
    public List<ResearchReport> findByProjectIdOrderByCreatedAtDesc(Long projectId) {
        return reportJpa.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(ResearchReportJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<ResearchReport> findByTaskId(Long taskId) {
        return reportJpa.findByTaskId(taskId).map(ResearchReportJpaEntity::toDomain);
    }

    @Override
    public int confirmIfPending(Long id, Instant confirmedAt, Instant updatedAt) {
        return reportJpa.confirmIfPending(id, confirmedAt, updatedAt);
    }

    @Override
    public int rejectIfPending(Long id, String reason, Instant updatedAt) {
        return reportJpa.rejectIfPending(id, reason, updatedAt);
    }
}

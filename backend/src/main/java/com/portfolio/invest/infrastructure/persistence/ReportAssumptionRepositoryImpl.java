package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.ReportAssumption;
import com.portfolio.invest.domain.research.ReportAssumptionRepository;
import java.util.List;
import org.springframework.stereotype.Repository;

// 事务边界在 application 层（P2）；本类不挂 @Transactional（照 ResearchProjectRepositoryImpl 先例）。
@Repository
public class ReportAssumptionRepositoryImpl implements ReportAssumptionRepository {

    private final ReportAssumptionJpaRepository assumptionJpa;

    public ReportAssumptionRepositoryImpl(ReportAssumptionJpaRepository assumptionJpa) {
        this.assumptionJpa = assumptionJpa;
    }

    @Override
    public void saveAll(Long reportId, List<ReportAssumption> assumptions) {
        // append-only（随定稿一次性落库）：不整替不删除；saveAllAndFlush 使 UNIQUE(report_id, seq)
        // 与 FK 违例事务内早抛
        assumptionJpa.saveAllAndFlush(assumptions.stream()
                .map(a -> ReportAssumptionJpaEntity.fromDomain(reportId, a)).toList());
    }

    @Override
    public List<ReportAssumption> findByReportId(Long reportId) {
        return assumptionJpa.findByReportIdOrderBySeqAsc(reportId).stream()
                .map(ReportAssumptionJpaEntity::toDomain).toList();
    }
}

package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportAssumptionJpaRepository extends JpaRepository<ReportAssumptionJpaEntity, Long> {
    List<ReportAssumptionJpaEntity> findByReportIdOrderBySeqAsc(Long reportId);
}

package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportAnchorJpaRepository extends JpaRepository<ReportAnchorJpaEntity, Long> {
    List<ReportAnchorJpaEntity> findByReportIdOrderByIdAsc(Long reportId);
}

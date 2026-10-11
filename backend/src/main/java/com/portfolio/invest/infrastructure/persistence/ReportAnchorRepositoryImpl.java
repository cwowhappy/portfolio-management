package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.ReportAnchor;
import com.portfolio.invest.domain.research.ReportAnchorRepository;
import java.util.List;
import org.springframework.stereotype.Repository;

// 事务边界在 application 层（P2）；本类不挂 @Transactional（照 ResearchProjectRepositoryImpl 先例）。
@Repository
public class ReportAnchorRepositoryImpl implements ReportAnchorRepository {

    private final ReportAnchorJpaRepository anchorJpa;

    public ReportAnchorRepositoryImpl(ReportAnchorJpaRepository anchorJpa) {
        this.anchorJpa = anchorJpa;
    }

    @Override
    public void saveAll(Long reportId, List<ReportAnchor> anchors) {
        // append-only（审计真相，§3.8）：不整替不删除；saveAllAndFlush 与 save 系列同语义——FK 违例事务内早抛
        anchorJpa.saveAllAndFlush(anchors.stream()
                .map(a -> ReportAnchorJpaEntity.fromDomain(reportId, a)).toList());
    }

    @Override
    public List<ReportAnchor> findByReportId(Long reportId) {
        return anchorJpa.findByReportIdOrderByIdAsc(reportId).stream()
                .map(ReportAnchorJpaEntity::toDomain).toList();
    }
}

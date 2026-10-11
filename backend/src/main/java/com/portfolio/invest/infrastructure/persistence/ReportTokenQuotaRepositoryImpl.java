package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.ReportTokenQuota;
import com.portfolio.invest.domain.research.ReportTokenQuotaRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Repository;

// 事务边界在 application 层（P2）；本类不挂 @Transactional（照 PortfolioRepositoryImpl 先例，
// settle 的 @Modifying 原生查询由调用方的事务包裹）。
@Repository
public class ReportTokenQuotaRepositoryImpl implements ReportTokenQuotaRepository {

    private final ReportTokenQuotaJpaRepository quotaJpa;

    public ReportTokenQuotaRepositoryImpl(ReportTokenQuotaJpaRepository quotaJpa) {
        this.quotaJpa = quotaJpa;
    }

    @Override
    public Optional<ReportTokenQuota> findByUserIdAndDate(Long userId, LocalDate date) {
        return quotaJpa.findByUserIdAndQuotaDate(userId, date).map(ReportTokenQuotaJpaEntity::toDomain);
    }

    @Override
    public void settle(Long userId, LocalDate date, long delta) {
        quotaJpa.settle(userId, date, delta);
    }
}

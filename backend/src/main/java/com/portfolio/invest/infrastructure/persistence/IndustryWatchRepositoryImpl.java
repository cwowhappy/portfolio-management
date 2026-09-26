package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.industry.IndustryWatchItem;
import com.portfolio.invest.domain.industry.IndustryWatchRepository;
import java.util.List;
import org.springframework.stereotype.Repository;

// 事务边界在 application 层；本类不挂 @Transactional。
@Repository
public class IndustryWatchRepositoryImpl implements IndustryWatchRepository {

    private final IndustryWatchItemJpaRepository jpa;

    public IndustryWatchRepositoryImpl(IndustryWatchItemJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<IndustryWatchItem> findByUserId(Long userId) {
        return jpa.findByUserIdOrderByAddedAtDesc(userId).stream().map(IndustryWatchItemJpaEntity::toDomain).toList();
    }

    @Override
    public boolean existsByUserIdAndIndustryCode(Long userId, String industryCode) {
        return jpa.existsByUserIdAndIndustryCode(userId, industryCode);
    }

    @Override
    public void save(IndustryWatchItem item) {
        if (jpa.existsByUserIdAndIndustryCode(item.userId(), item.industryCode())) {
            return; // 幂等：重复关注直接成功（先查后插，照 Watchlist 先例）
        }
        jpa.save(IndustryWatchItemJpaEntity.fromDomain(item));
    }

    @Override
    public void deleteByUserIdAndIndustryCode(Long userId, String industryCode) {
        jpa.deleteByUserIdAndIndustryCode(userId, industryCode);
    }
}

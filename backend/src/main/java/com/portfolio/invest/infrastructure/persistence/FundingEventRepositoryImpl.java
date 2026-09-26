package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.industry.FundingEvent;
import com.portfolio.invest.domain.industry.FundingEventRepository;
import com.portfolio.invest.domain.industry.UnlistedCompanyRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 融资事件仓储实现（照 IndustryWatchRepositoryImpl 先例）：事务边界在 application 层，
 * 本类不挂 @Transactional；upsert = 先按幂等键查、存在则更新非键字段并 save、否则插入。
 */
@Repository
public class FundingEventRepositoryImpl implements FundingEventRepository {

    private final IndustryFundingEventJpaRepository jpa;

    public FundingEventRepositoryImpl(IndustryFundingEventJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public UnlistedCompanyRepository.UpsertOutcome upsert(FundingEvent event) {
        var existing = jpa.findByEventDateAndCompanyNameAndRound(
                event.eventDate(), event.companyName(), event.round().name());
        if (existing.isPresent()) {
            IndustryFundingEventJpaEntity entity = existing.get(); // 幂等：命中键更新非键字段（先查后改，照 Watch 先例）
            entity.applyNonKeyFieldsFrom(IndustryFundingEventJpaEntity.fromDomain(event));
            jpa.save(entity);
            return new UnlistedCompanyRepository.UpsertOutcome(false);
        }
        jpa.save(IndustryFundingEventJpaEntity.fromDomain(event));
        return new UnlistedCompanyRepository.UpsertOutcome(true);
    }

    @Override
    public List<FundingEvent> findByIndustrySince(String industryCode, LocalDate since) {
        return jpa.findByIndustryCodeAndEventDateGreaterThanEqualOrderByEventDateDesc(industryCode, since)
                .stream().map(IndustryFundingEventJpaEntity::toDomain).toList();
    }

    @Override
    public void deleteById(Long id) {
        jpa.deleteById(id); // SimpleJpaRepository 内部 findById().ifPresent(delete)：删不存在不抛
    }
}

package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.industry.UnlistedCompany;
import com.portfolio.invest.domain.industry.UnlistedCompanyRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 未上市策展企业仓储实现（照 IndustryWatchRepositoryImpl 先例）：事务边界在 application 层，
 * 本类不挂 @Transactional；upsert = 先按幂等键查、存在则更新非键字段并 save、否则插入。
 */
@Repository
public class UnlistedCompanyRepositoryImpl implements UnlistedCompanyRepository {

    private final IndustryUnlistedCompanyJpaRepository jpa;

    public UnlistedCompanyRepositoryImpl(IndustryUnlistedCompanyJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<UnlistedCompany> findByIndustry(String industryCode) {
        // 排序契约：lastFundingDate DESC NULLS LAST，同日按轮次序（FundingRound.order）倒序。
        // 轮次序是 Java 枚举声明序，库内无对应表达式——取回后在应用侧排序（表量级百~千行）。
        // 注意 nullsLast(naturalOrder()).reversed() 会把 null 翻到最前，须用 nullsLast(reverseOrder())。
        return jpa.findByIndustryCode(industryCode).stream()
                .map(IndustryUnlistedCompanyJpaEntity::toDomain)
                .sorted(Comparator.comparing(UnlistedCompany::lastFundingDate,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(c -> c.latestRound().order(), Comparator.reverseOrder()))
                .toList();
    }

    @Override
    public long countByIndustry(String industryCode) {
        return jpa.countByIndustryCode(industryCode);
    }

    @Override
    public UpsertOutcome upsert(UnlistedCompany company) {
        var existing = jpa.findByIndustryCodeAndCompanyName(company.industryCode(), company.companyName());
        if (existing.isPresent()) {
            IndustryUnlistedCompanyJpaEntity entity = existing.get(); // 幂等：命中键更新非键字段（先查后改，照 Watch 先例）
            entity.applyNonKeyFieldsFrom(IndustryUnlistedCompanyJpaEntity.fromDomain(company));
            jpa.save(entity);
            return new UpsertOutcome(false);
        }
        jpa.save(IndustryUnlistedCompanyJpaEntity.fromDomain(company));
        return new UpsertOutcome(true);
    }

    @Override
    public Optional<UnlistedCompany> findById(Long id) {
        return jpa.findById(id).map(IndustryUnlistedCompanyJpaEntity::toDomain);
    }

    @Override
    public void deleteById(Long id) {
        jpa.deleteById(id); // SimpleJpaRepository 内部 findById().ifPresent(delete)：删不存在不抛
    }
}

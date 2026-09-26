package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IndustryUnlistedCompanyJpaRepository extends JpaRepository<IndustryUnlistedCompanyJpaEntity, Long> {

    /** CSV upsert 幂等键查询。 */
    Optional<IndustryUnlistedCompanyJpaEntity> findByIndustryCodeAndCompanyName(String industryCode, String companyName);

    List<IndustryUnlistedCompanyJpaEntity> findByIndustryCode(String industryCode);

    long countByIndustryCode(String industryCode);
}

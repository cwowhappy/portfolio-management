package com.portfolio.invest.infrastructure.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IndustryFundingEventJpaRepository extends JpaRepository<IndustryFundingEventJpaEntity, Long> {

    /** CSV upsert 幂等键查询（同日同企同轮视为同一事件）。 */
    Optional<IndustryFundingEventJpaEntity> findByEventDateAndCompanyNameAndRound(
            LocalDate eventDate, String companyName, String round);

    List<IndustryFundingEventJpaEntity> findByIndustryCodeAndEventDateGreaterThanEqualOrderByEventDateDesc(
            String industryCode, LocalDate since);
}

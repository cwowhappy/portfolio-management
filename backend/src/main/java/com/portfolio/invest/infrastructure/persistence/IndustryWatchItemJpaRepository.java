package com.portfolio.invest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IndustryWatchItemJpaRepository extends JpaRepository<IndustryWatchItemJpaEntity, Long> {

    List<IndustryWatchItemJpaEntity> findByUserIdOrderByAddedAtDesc(Long userId);

    boolean existsByUserIdAndIndustryCode(Long userId, String industryCode);

    void deleteByUserIdAndIndustryCode(Long userId, String industryCode);
}

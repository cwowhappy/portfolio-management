package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AllocationPlanJpaRepository extends JpaRepository<AllocationPlanJpaEntity, Long> {
    List<AllocationPlanJpaEntity> findByUserIdOrderByIdDesc(Long userId);
    Optional<AllocationPlanJpaEntity> findByIdAndUserId(Long id, Long userId);
    Optional<AllocationPlanJpaEntity> findFirstByUserIdAndActiveOrderByIdAsc(Long userId, boolean active);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AllocationPlanJpaEntity p SET p.active = false WHERE p.userId = :userId AND p.active = true")
    int deactivateAllByUserId(@Param("userId") Long userId);
}

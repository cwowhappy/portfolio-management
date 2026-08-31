package com.portfolio.invest.domain.allocation;

import java.util.List;
import java.util.Optional;

/** 配置方案仓库端口：归属过滤（userId）在用例层双重保障。 */
public interface AllocationPlanRepository {
    List<AllocationPlan> findByUserId(Long userId);
    Optional<AllocationPlan> findByIdAndUserId(Long id, Long userId);
    Optional<AllocationPlan> findActiveByUserId(Long userId);
    AllocationPlan save(AllocationPlan plan);
    void deactivateAllByUserId(Long userId);
    void deleteById(Long id);
}

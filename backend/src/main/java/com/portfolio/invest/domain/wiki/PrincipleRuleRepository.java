package com.portfolio.invest.domain.wiki;

import java.util.List;
import java.util.Optional;

/** 原则纪律规则仓库端口。 */
public interface PrincipleRuleRepository {
    List<PrincipleRule> findByUserId(Long userId);
    Optional<PrincipleRule> findByIdAndUserId(Long id, Long userId);
    PrincipleRule save(PrincipleRule rule);
    void deleteById(Long id);
}

package com.portfolio.invest.domain.allocation;

import java.util.Optional;

/** 风险测评仓库端口：save 为 upsert 语义（同用户覆盖，仅存最新一条）。 */
public interface RiskAssessmentRepository {
    Optional<RiskAssessment> findByUserId(Long userId);
    RiskAssessment save(RiskAssessment assessment);
}

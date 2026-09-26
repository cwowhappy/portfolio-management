package com.portfolio.invest.domain.industry;

import java.time.LocalDate;
import java.util.List;

/**
 * 融资事件端口（设计规格 §三）：findByIndustrySince 窗口过滤（读侧默认近 24 月）；
 * upsert 命中幂等键（eventDate+companyName+round）更新非键字段。
 */
public interface FundingEventRepository {

    /** 与 {@link UnlistedCompanyRepository#upsert} 共用 upsert 产物形状（跨两表同构语义）。 */
    UnlistedCompanyRepository.UpsertOutcome upsert(FundingEvent event);

    List<FundingEvent> findByIndustrySince(String industryCode, LocalDate since);

    void deleteById(Long id);
}

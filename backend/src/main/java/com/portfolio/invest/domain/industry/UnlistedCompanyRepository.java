package com.portfolio.invest.domain.industry;

import java.util.List;
import java.util.Optional;

/**
 * 未上市策展企业端口（设计规格 §三）：findByIndustry 排序 lastFundingDate DESC NULLS LAST、
 * 同日按轮次序倒序；upsert 命中幂等键（industryCode+companyName）更新非键字段。
 */
public interface UnlistedCompanyRepository {

    /** upsert 产物：inserted=true 新插入 / false 命中幂等键更新。 */
    record UpsertOutcome(boolean inserted) {}

    List<UnlistedCompany> findByIndustry(String industryCode);

    long countByIndustry(String industryCode);

    UpsertOutcome upsert(UnlistedCompany company);

    Optional<UnlistedCompany> findById(Long id);

    void deleteById(Long id);
}

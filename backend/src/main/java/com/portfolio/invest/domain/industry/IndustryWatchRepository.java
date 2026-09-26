package com.portfolio.invest.domain.industry;

import java.util.List;

/** 行业关注端口：归属过滤（userId）在用例层双重保障。 */
public interface IndustryWatchRepository {
    List<IndustryWatchItem> findByUserId(Long userId); // addedAt 倒序

    boolean existsByUserIdAndIndustryCode(Long userId, String industryCode);

    /** 幂等：重复关注同一行业直接成功（UNIQUE 冲突不外抛，先查后插）。 */
    void save(IndustryWatchItem item);

    /** 幂等：删除不存在的关注行不抛。 */
    void deleteByUserIdAndIndustryCode(Long userId, String industryCode);
}

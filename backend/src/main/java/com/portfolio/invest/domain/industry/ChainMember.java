package com.portfolio.invest.domain.industry;

/**
 * 产业链环节成员（设计规格 §三）：memberType 为 "LISTED"/"UNLISTED" 字符串——照表 CHECK
 * 口径，不引枚举减少映射层；LISTED 必带 stockCode（行情台跳转）、UNLISTED 必带
 * unlistedCompanyId（策展企业引用，删除时 ON DELETE SET NULL）；displayName 为展示名快照。
 * id 由库生成（插入时为 null）。
 */
public record ChainMember(Long id, String memberType, String stockCode, Long unlistedCompanyId,
                          String displayName) {
}

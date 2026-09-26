package com.portfolio.invest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** V20 industry_chain_member 仓储接口（全文档替换保存路径的成员插入用）。 */
public interface IndustryChainMemberJpaRepository extends JpaRepository<IndustryChainMemberJpaEntity, Long> {
}

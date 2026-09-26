package com.portfolio.invest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** V20 industry_chain_stage 仓储接口（全文档替换保存路径的层级插入用）。 */
public interface IndustryChainStageJpaRepository extends JpaRepository<IndustryChainStageJpaEntity, Long> {
}

package com.portfolio.invest.infrastructure.persistence.intelligence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * intelligence_news_raw 的 Spring Data 门面。backend 对 raw 表只读（collector 契约表），
 * 读写路径经 {@link IntelligenceNewsRepositoryImpl} 原生 SQL（trgm/JSONB containment），
 * 本接口为后续任务按需扩展派生查询预留（接口在上下文启动时即校验实体映射）。
 */
public interface IntelligenceNewsRawJpaRepository extends JpaRepository<IntelligenceNewsRawJpaEntity, Long> {
}

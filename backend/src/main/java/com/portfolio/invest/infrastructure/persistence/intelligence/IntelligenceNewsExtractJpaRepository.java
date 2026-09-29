package com.portfolio.invest.infrastructure.persistence.intelligence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * intelligence_news_extract 的 Spring Data 门面。写入经
 * {@link IntelligenceNewsRepositoryImpl} 的 INSERT … ON CONFLICT（语义要求整体置换），
 * 本接口为后续任务按需扩展派生查询预留（接口在上下文启动时即校验实体映射）。
 */
public interface IntelligenceNewsExtractJpaRepository
        extends JpaRepository<IntelligenceNewsExtractJpaEntity, Long> {

    /** 按 UNIQUE(news_raw_id) 取一对一抽取行。 */
    Optional<IntelligenceNewsExtractJpaEntity> findByNewsRawId(Long newsRawId);
}

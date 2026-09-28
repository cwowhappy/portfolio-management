package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.CheckRecord;
import com.portfolio.invest.domain.research.FalsifierHit;
import com.portfolio.invest.domain.research.ResearchCheckRepository;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * append-only 留痕仓库实现（NFR-2）：仅 insert 与读方法，不暴露 update——
 * 检查留痕与证伪命中留痕均无更新用例（domain 亦不提供更新入口）。
 * 事务边界在 application 层（照 ResearchProjectRepositoryImpl 先例）。
 */
@Repository
public class ResearchCheckRepositoryImpl implements ResearchCheckRepository {

    private final ResearchCheckRecordJpaRepository checkJpa;
    private final ResearchFalsifierHitJpaRepository hitJpa;

    public ResearchCheckRepositoryImpl(ResearchCheckRecordJpaRepository checkJpa,
                                       ResearchFalsifierHitJpaRepository hitJpa) {
        this.checkJpa = checkJpa;
        this.hitJpa = hitJpa;
    }

    @Override
    public CheckRecord insert(CheckRecord record) {
        // saveAndFlush：约束违例事务内早抛（照 save 系列先例）
        return checkJpa.saveAndFlush(ResearchCheckRecordJpaEntity.fromDomain(record)).toDomain();
    }

    @Override
    public FalsifierHit insertHit(FalsifierHit hit) {
        return hitJpa.saveAndFlush(ResearchFalsifierHitJpaEntity.fromDomain(hit)).toDomain();
    }

    @Override
    public List<FalsifierHit> findHits(Long projectId) {
        return hitJpa.findByProjectIdOrderByCreatedAtDescIdDesc(projectId).stream()
                .map(ResearchFalsifierHitJpaEntity::toDomain)
                .toList();
    }
}

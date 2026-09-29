package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.FalsifierReview;
import com.portfolio.invest.domain.research.FalsifierReviewRepository;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 证伪评审留痕仓库实现（NFR-2）：评审行仅 insert 与读；attachReview 为 hit 表唯一合法更新
 * （append-only 例外理由见 {@link FalsifierReviewRepository} 端口 javadoc——review_id 软引用
 * 设计上即评审后回填，且仅未评审行生效、不覆盖）。事务边界在 application 层。
 */
@Repository
public class FalsifierReviewRepositoryImpl implements FalsifierReviewRepository {

    private final ResearchFalsifierReviewJpaRepository reviewJpa;
    private final ResearchFalsifierHitJpaRepository hitJpa;

    public FalsifierReviewRepositoryImpl(ResearchFalsifierReviewJpaRepository reviewJpa,
                                         ResearchFalsifierHitJpaRepository hitJpa) {
        this.reviewJpa = reviewJpa;
        this.hitJpa = hitJpa;
    }

    @Override
    public FalsifierReview insert(FalsifierReview review) {
        // saveAndFlush：约束违例事务内早抛（照 ResearchCheckRepositoryImpl 先例）
        return reviewJpa.saveAndFlush(ResearchFalsifierReviewJpaEntity.fromDomain(review)).toDomain();
    }

    @Override
    public List<FalsifierReview> findReviews(Long projectId) {
        return reviewJpa.findByProjectIdOrderByCreatedAtDescIdDesc(projectId).stream()
                .map(ResearchFalsifierReviewJpaEntity::toDomain)
                .toList();
    }

    @Override
    public void attachReview(Long hitId, Long reviewId) {
        // 已回填行不覆盖（首评占据软引用）；0 行 = 未评审行不存在或已回填，均合法静默
        hitJpa.attachReviewIfUnreviewed(hitId, reviewId);
    }
}

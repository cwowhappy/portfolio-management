package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.Review;
import com.portfolio.invest.domain.research.ReviewRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 复盘仓库实现：saveAndFlush 约束违例事务内早抛（照 ResearchProjectRepositoryImpl 先例）；事务边界在 application 层。 */
@Repository
public class ReviewRepositoryImpl implements ReviewRepository {

    private final ResearchReviewJpaRepository reviewJpa;

    public ReviewRepositoryImpl(ResearchReviewJpaRepository reviewJpa) {
        this.reviewJpa = reviewJpa;
    }

    @Override
    public Review save(Review review) {
        return reviewJpa.saveAndFlush(ResearchReviewJpaEntity.fromDomain(review)).toDomain();
    }

    @Override
    public List<Review> findByProjectId(Long projectId) {
        return reviewJpa.findByProjectIdOrderByPeriodStartDescIdDesc(projectId).stream()
                .map(ResearchReviewJpaEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<Review> findByIdAndProjectId(Long id, Long projectId) {
        return reviewJpa.findByIdAndProjectId(id, projectId)
                .map(ResearchReviewJpaEntity::toDomain);
    }
}

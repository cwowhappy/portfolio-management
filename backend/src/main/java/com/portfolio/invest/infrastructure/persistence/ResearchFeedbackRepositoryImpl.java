package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.ResearchFeedback;
import com.portfolio.invest.domain.research.ResearchFeedbackRepository;
import org.springframework.stereotype.Repository;

/** 建议仓库实现：saveAndFlush 约束违例（如 review_id FK 孤儿）事务内早抛；事务边界在 application 层。 */
@Repository
public class ResearchFeedbackRepositoryImpl implements ResearchFeedbackRepository {

    private final ResearchFeedbackJpaRepository feedbackJpa;

    public ResearchFeedbackRepositoryImpl(ResearchFeedbackJpaRepository feedbackJpa) {
        this.feedbackJpa = feedbackJpa;
    }

    @Override
    public ResearchFeedback insert(ResearchFeedback feedback) {
        return feedbackJpa.saveAndFlush(ResearchFeedbackJpaEntity.fromDomain(feedback)).toDomain();
    }
}

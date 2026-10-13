package com.portfolio.invest.application.research;

import static com.portfolio.invest.application.research.ResearchServiceSupport.requireProject;

import com.portfolio.invest.application.research.ResearchViews.FeedbackView;
import com.portfolio.invest.domain.research.ResearchErrorCode;
import com.portfolio.invest.domain.research.ResearchException;
import com.portfolio.invest.domain.research.ResearchFeedback;
import com.portfolio.invest.domain.research.ResearchFeedbackRepository;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.Review;
import com.portfolio.invest.domain.research.ReviewRepository;
import java.time.Clock;

/**
 * 模板改进建议用例族（F16 只收集不生效）。2026-10-12 按用例族拆分自原
 * {@link ResearchApplicationService}（纯搬移，行为不变）；事务边界在门面方法上，
 * 本类为门面手工装配、非 Spring bean。
 */
public class ResearchFeedbackApplicationService {

    private final ResearchProjectRepository repository;
    private final ReviewRepository reviewRepository;
    private final ResearchFeedbackRepository feedbackRepository;
    private final Clock clock;

    public ResearchFeedbackApplicationService(ResearchProjectRepository repository,
                                              ReviewRepository reviewRepository,
                                              ResearchFeedbackRepository feedbackRepository,
                                              Clock clock) {
        this.repository = repository;
        this.reviewRepository = reviewRepository;
        this.feedbackRepository = feedbackRepository;
        this.clock = clock;
    }

    /**
     * 模板改进建议（F16 只收集不生效，v1 不做模板自动改写）：append-only 落库，无事件无回流副作用；
     * 域校验先于 review 资源解析（照 T2 先例：请求体非法 → 422 且零写库，reviewId 越项目 → 404）。
     */
    public FeedbackView submitFeedback(Long userId, Long projectId,
                                       ResearchApplicationService.SubmitFeedbackCommand cmd) {
        requireProject(repository, userId, projectId);
        ResearchFeedback feedback = ResearchFeedback.create(projectId, cmd.reviewId(), cmd.stage(), cmd.content(),
                clock.instant());
        if (cmd.reviewId() != null) {
            requireReview(projectId, cmd.reviewId());
        }
        return FeedbackView.from(feedbackRepository.insert(feedback));
    }

    /** 项目域内复盘行读取（照 requireReview 口径：越项目/不存在一律 NOT_FOUND，不泄漏存在性）。 */
    private Review requireReview(Long projectId, Long reviewId) {
        return reviewRepository.findByIdAndProjectId(reviewId, projectId)
                .orElseThrow(() -> new ResearchException(ResearchErrorCode.NOT_FOUND, "复盘不存在"));
    }
}

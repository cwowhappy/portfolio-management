package com.portfolio.invest.domain.allocation;

import java.time.Instant;
import java.util.Map;

/** 风险测评结果：8 题答卷评分后的总分与档位结论。每用户仅存最新一条（规格 FR-D3）。 */
public final class RiskAssessment {

    private final Long userId;
    private final int totalScore;
    private final RiskProfile profile;
    private final Map<String, String> answers;
    private final Instant assessedAt;

    private RiskAssessment(Long userId, int totalScore, RiskProfile profile,
                           Map<String, String> answers, Instant assessedAt) {
        this.userId = userId;
        this.totalScore = totalScore;
        this.profile = profile;
        this.answers = Map.copyOf(answers);
        this.assessedAt = assessedAt;
    }

    /** 评分入口（纯函数）：校验答卷完整合法后计分定档。answers：题目枚举名 → 选项 id。 */
    public static RiskAssessment grade(Long userId, Map<String, String> answers, Instant now) {
        Map<String, String> safe = Map.copyOf(answers);
        if (safe.size() != RiskQuestion.values().length) {
            throw new AllocationException(AllocationErrorCode.INVALID_ANSWERS, "答卷题目不完整");
        }
        int total = 0;
        for (RiskQuestion q : RiskQuestion.values()) {
            String optionId = safe.get(q.name());
            if (optionId == null) {
                throw new AllocationException(AllocationErrorCode.INVALID_ANSWERS, "缺少作答: " + q.name());
            }
            total += q.scoreOf(optionId);
        }
        return new RiskAssessment(userId, total, RiskProfile.fromTotalScore(total), safe, now);
    }

    /** 仓储回读用。 */
    public static RiskAssessment reconstitute(Long userId, int totalScore, RiskProfile profile,
                                              Map<String, String> answers, Instant assessedAt) {
        return new RiskAssessment(userId, totalScore, profile, answers, assessedAt);
    }

    public Long userId() { return userId; }
    public int totalScore() { return totalScore; }
    public RiskProfile profile() { return profile; }
    public Map<String, String> answers() { return answers; }
    public Instant assessedAt() { return assessedAt; }
}

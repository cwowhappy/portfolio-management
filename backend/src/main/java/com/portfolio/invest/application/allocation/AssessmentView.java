package com.portfolio.invest.application.allocation;

import com.portfolio.invest.domain.allocation.RiskAssessment;
import com.portfolio.invest.domain.allocation.RiskProfile;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AssessmentView(int totalScore, RiskProfile profile, String profileName,
                             List<WeightView> weights, Map<String, String> answers, Instant assessedAt) {
    public static AssessmentView from(RiskAssessment a) {
        return new AssessmentView(a.totalScore(), a.profile(), a.profile().displayName(),
                WeightView.ordered(a.profile().recommendedWeights()), a.answers(), a.assessedAt());
    }
}

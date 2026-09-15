package com.portfolio.invest.application.allocation;

import com.portfolio.invest.domain.allocation.RiskQuestion;
import java.util.Arrays;
import java.util.List;

public record QuestionnaireView(List<QuestionView> questions) {
    public static QuestionnaireView fromBuiltin() {
        return new QuestionnaireView(Arrays.stream(RiskQuestion.values())
                .map(q -> new QuestionView(q.name(), q.dimension(), q.text(),
                        q.options().stream().map(o -> new OptionView(o.id(), o.text())).toList()))
                .toList());
    }
}

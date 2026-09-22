package com.portfolio.invest.application.wiki;

import com.portfolio.invest.domain.wiki.PrincipleMetric;
import com.portfolio.invest.domain.wiki.PrincipleRule;
import java.math.BigDecimal;
import java.time.Instant;

public record PrincipleRuleView(Long id, PrincipleMetric metric, BigDecimal threshold, boolean enabled,
                                String description, Instant createdAt, Instant updatedAt) {

    public static PrincipleRuleView from(PrincipleRule r) {
        return new PrincipleRuleView(r.id(), r.metric(), r.threshold(), r.enabled(),
                r.description(), r.createdAt(), r.updatedAt());
    }
}

package com.portfolio.invest.application.allocation;

import com.portfolio.invest.domain.allocation.AllocationPlan;
import com.portfolio.invest.domain.allocation.PlanSource;
import com.portfolio.invest.domain.allocation.RebalanceFrequency;
import java.time.Instant;
import java.util.List;

public record PlanView(Long id, String name, PlanSource source, List<WeightView> weights, boolean active,
                       RebalanceFrequency rebalanceFrequency, Instant lastRebalancedAt) {
    public static PlanView from(AllocationPlan p) {
        return new PlanView(p.id(), p.name(), p.source(), WeightView.ordered(p.weights()), p.active(),
                p.rebalanceFrequency(), p.lastRebalancedAt());
    }
}

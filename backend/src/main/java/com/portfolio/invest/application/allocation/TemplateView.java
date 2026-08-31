package com.portfolio.invest.application.allocation;

import com.portfolio.invest.domain.allocation.AllocationTemplate;
import java.util.List;

public record TemplateView(String id, String name, List<WeightView> weights) {
    public static TemplateView from(AllocationTemplate t) {
        return new TemplateView(t.name(), t.displayName(), WeightView.ordered(t.weights()));
    }
}

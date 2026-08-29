package com.portfolio.invest.application.portfolio;

import com.portfolio.invest.domain.portfolio.GroupType;
import com.portfolio.invest.domain.portfolio.HoldingGroup;

import java.math.BigDecimal;

public record GroupView(Long id, String name, GroupType type, int positionCount, BigDecimal cashBalance) {
    public static GroupView from(HoldingGroup g, int positionCount, BigDecimal cashBalance) {
        return new GroupView(g.id(), g.name(), g.type(), positionCount, cashBalance);
    }
}

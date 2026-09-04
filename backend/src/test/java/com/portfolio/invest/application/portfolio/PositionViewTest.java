package com.portfolio.invest.application.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.portfolio.Position;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PositionView 装配：行情缺失/零持仓时的空值语义。 */
class PositionViewTest {

    @DisplayName("零持仓数量时市值与浮动盈亏为null")
    @Test
    void givenZeroQuantityPosition_whenBuildView_thenMarketValueAndFloatingPnlNull() {
        // 新建未买入的持仓：数量 0，即使有行情也无市值（避免 0 成本除零与误导性 0% 盈亏）
        Position p = Position.create(10L, 1L, "600519", "贵州茅台", Instant.now());
        Quote q = new Quote("600519", "贵州茅台", 120, 0, 0, 0, 0, 0, 100, 0, 0, null, null, "");

        var view = PositionView.from(p, q);

        assertThat(view.price()).isEqualByComparingTo("120");
        assertThat(view.marketValue()).isNull();
        assertThat(view.floatingPnl()).isNull();
        assertThat(view.pnlRatio()).isNull();
    }
}

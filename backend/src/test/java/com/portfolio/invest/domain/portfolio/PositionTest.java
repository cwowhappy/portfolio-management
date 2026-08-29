package com.portfolio.invest.domain.portfolio;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PositionTest {

    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    private static Position newPosition() {
        return Position.create(1L, 2L, "600519", "贵州茅台", NOW);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void 买入建立数量与成本() {
        Position p = newPosition().applyBuy(bd("1500"), bd("100"), bd("5"));
        assertThat(p.quantity()).isEqualByComparingTo("100");
        assertThat(p.costBasis()).isEqualByComparingTo("150005"); // 1500*100 + 5
        assertThat(p.totalBuyCost()).isEqualByComparingTo("150005");
        assertThat(p.avgCost()).isEqualByComparingTo("1500.05");
        assertThat(p.netCashFlow()).isEqualByComparingTo("-150005");
    }

    @Test
    void 多次买入按加权平均() {
        Position p = newPosition()
                .applyBuy(bd("100"), bd("100"), bd("0"))   // cost 10000
                .applyBuy(bd("200"), bd("100"), bd("0"));  // cost 20000
        assertThat(p.quantity()).isEqualByComparingTo("200");
        assertThat(p.avgCost()).isEqualByComparingTo("150");
        assertThat(p.totalBuyCost()).isEqualByComparingTo("30000");
    }

    @Test
    void 卖出计算已实现收益并减少持仓() {
        Position p = newPosition()
                .applyBuy(bd("100"), bd("100"), bd("0"))   // avgCost 100
                .applySell(bd("120"), bd("40"), bd("0"));  // realized (120-100)*40 = 800
        assertThat(p.quantity()).isEqualByComparingTo("60");
        assertThat(p.realizedPnl()).isEqualByComparingTo("800");
        assertThat(p.costBasis()).isEqualByComparingTo("6000"); // 100*60
        assertThat(p.netCashFlow()).isEqualByComparingTo("-5200"); // -10000 + 4800
    }

    @Test
    void 卖出手续费从已实现收益扣除() {
        Position p = newPosition()
                .applyBuy(bd("100"), bd("100"), bd("0"))
                .applySell(bd("120"), bd("40"), bd("10")); // proceeds 4800-10, realized 4790-4000=790
        assertThat(p.realizedPnl()).isEqualByComparingTo("790");
    }

    @Test
    void 现金分红降低摊薄成本() {
        Position p = newPosition()
                .applyBuy(bd("100"), bd("100"), bd("0"))     // costBasis 10000
                .applyCashDividend(bd("500"));               // 每股分红 5 元
        assertThat(p.cumulativeCashDividend()).isEqualByComparingTo("500");
        assertThat(p.costBasis()).isEqualByComparingTo("9500");
        assertThat(p.avgCost()).isEqualByComparingTo("95");
        assertThat(p.netCashFlow()).isEqualByComparingTo("-9500"); // -10000 + 500
    }

    @Test
    void 送股增加股数摊薄每股成本() {
        Position p = newPosition()
                .applyBuy(bd("100"), bd("100"), bd("0"))   // 100 股
                .applyStockDividend(bd("0.3"));            // 10送3
        assertThat(p.quantity()).isEqualByComparingTo("130");
        assertThat(p.costBasis()).isEqualByComparingTo("10000"); // 不变
        assertThat(p.avgCost()).isEqualByComparingTo("76.92");   // 10000/130，四舍五入两位
    }

    @Test
    void 分红后卖出按摊薄成本匹配() {
        Position p = newPosition()
                .applyBuy(bd("100"), bd("100"), bd("0"))     // avgCost 100
                .applyCashDividend(bd("500"))                // avgCost 95
                .applySell(bd("120"), bd("50"), bd("0"));    // realized (120-95)*50 = 1250
        assertThat(p.realizedPnl()).isEqualByComparingTo("1250");
        assertThat(p.quantity()).isEqualByComparingTo("50");
    }

    @Test
    void 部分卖出后摊薄成本不变() {
        Position p = newPosition()
                .applyBuy(bd("100"), bd("100"), bd("0"))
                .applySell(bd("120"), bd("40"), bd("0"));
        assertThat(p.avgCost()).isEqualByComparingTo("100"); // 摊薄成本不因卖出改变
    }

    @Test
    void 全部卖出后数量归零成本清零() {
        Position p = newPosition()
                .applyBuy(bd("100"), bd("100"), bd("0"))
                .applySell(bd("120"), bd("100"), bd("0"));
        assertThat(p.quantity()).isEqualByComparingTo("0");
        assertThat(p.costBasis()).isEqualByComparingTo("0");
        assertThat(p.avgCost()).isNull();
        assertThat(p.realizedPnl()).isEqualByComparingTo("2000");
    }

    @Test
    void 卖出超过持仓抛出异常() {
        Position p = newPosition().applyBuy(bd("100"), bd("100"), bd("0"));
        assertThatThrownBy(() -> p.applySell(bd("120"), bd("101"), bd("0")))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("卖出数量超过持仓");
    }
}

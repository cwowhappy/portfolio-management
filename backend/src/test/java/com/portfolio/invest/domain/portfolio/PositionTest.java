package com.portfolio.invest.domain.portfolio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PositionTest {

    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    private static Position newPosition() {
        return Position.create(1L, 2L, "600519", "贵州茅台", NOW);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @DisplayName("买入建立数量与成本")
    @Test
    void buyEstablishesQuantityAndCost() {
        Position p = newPosition().applyBuy(bd("1500"), bd("100"), bd("5"));
        assertThat(p.quantity()).isEqualByComparingTo("100");
        assertThat(p.costBasis()).isEqualByComparingTo("150005"); // 1500*100 + 5
        assertThat(p.totalBuyCost()).isEqualByComparingTo("150005");
        assertThat(p.avgCost()).isEqualByComparingTo("1500.05");
        assertThat(p.netCashFlow()).isEqualByComparingTo("-150005");
    }

    @DisplayName("多次买入按加权平均")
    @Test
    void repeatedBuysUseWeightedAverageCost() {
        Position p = newPosition()
                .applyBuy(bd("100"), bd("100"), bd("0"))   // cost 10000
                .applyBuy(bd("200"), bd("100"), bd("0"));  // cost 20000
        assertThat(p.quantity()).isEqualByComparingTo("200");
        assertThat(p.avgCost()).isEqualByComparingTo("150");
        assertThat(p.totalBuyCost()).isEqualByComparingTo("30000");
    }

    @DisplayName("卖出计算已实现收益并减少持仓")
    @Test
    void sellComputesRealizedPnlAndReducesPosition() {
        Position p = newPosition()
                .applyBuy(bd("100"), bd("100"), bd("0"))   // avgCost 100
                .applySell(bd("120"), bd("40"), bd("0"));  // realized (120-100)*40 = 800
        assertThat(p.quantity()).isEqualByComparingTo("60");
        assertThat(p.realizedPnl()).isEqualByComparingTo("800");
        assertThat(p.costBasis()).isEqualByComparingTo("6000"); // 100*60
        assertThat(p.netCashFlow()).isEqualByComparingTo("-5200"); // -10000 + 4800
    }

    @DisplayName("卖出手续费从已实现收益扣除")
    @Test
    void sellFeeDeductedFromRealizedPnl() {
        Position p = newPosition()
                .applyBuy(bd("100"), bd("100"), bd("0"))
                .applySell(bd("120"), bd("40"), bd("10")); // proceeds 4800-10, realized 4790-4000=790
        assertThat(p.realizedPnl()).isEqualByComparingTo("790");
    }

    @DisplayName("现金分红降低摊薄成本")
    @Test
    void cashDividendLowersDilutedCost() {
        Position p = newPosition()
                .applyBuy(bd("100"), bd("100"), bd("0"))     // costBasis 10000
                .applyCashDividend(bd("500"));               // 每股分红 5 元
        assertThat(p.cumulativeCashDividend()).isEqualByComparingTo("500");
        assertThat(p.costBasis()).isEqualByComparingTo("9500");
        assertThat(p.avgCost()).isEqualByComparingTo("95");
        assertThat(p.netCashFlow()).isEqualByComparingTo("-9500"); // -10000 + 500
    }

    @DisplayName("送股增加股数摊薄每股成本")
    @Test
    void stockDividendIncreasesSharesAndDilutesCost() {
        Position p = newPosition()
                .applyBuy(bd("100"), bd("100"), bd("0"))   // 100 股
                .applyStockDividend(bd("0.3"));            // 10送3
        assertThat(p.quantity()).isEqualByComparingTo("130");
        assertThat(p.costBasis()).isEqualByComparingTo("10000"); // 不变
        assertThat(p.avgCost()).isEqualByComparingTo("76.92");   // 10000/130，四舍五入两位
    }

    @DisplayName("分红后卖出按摊薄成本匹配")
    @Test
    void sellAfterDividendMatchesDilutedCost() {
        Position p = newPosition()
                .applyBuy(bd("100"), bd("100"), bd("0"))     // avgCost 100
                .applyCashDividend(bd("500"))                // avgCost 95
                .applySell(bd("120"), bd("50"), bd("0"));    // realized (120-95)*50 = 1250
        assertThat(p.realizedPnl()).isEqualByComparingTo("1250");
        assertThat(p.quantity()).isEqualByComparingTo("50");
    }

    @DisplayName("部分卖出后摊薄成本不变")
    @Test
    void partialSellKeepsDilutedCostUnchanged() {
        Position p = newPosition()
                .applyBuy(bd("100"), bd("100"), bd("0"))
                .applySell(bd("120"), bd("40"), bd("0"));
        assertThat(p.avgCost()).isEqualByComparingTo("100"); // 摊薄成本不因卖出改变
    }

    @DisplayName("全部卖出后数量归零成本清零")
    @Test
    void fullSellZeroesQuantityAndClearsCost() {
        Position p = newPosition()
                .applyBuy(bd("100"), bd("100"), bd("0"))
                .applySell(bd("120"), bd("100"), bd("0"));
        assertThat(p.quantity()).isEqualByComparingTo("0");
        assertThat(p.costBasis()).isEqualByComparingTo("0");
        assertThat(p.avgCost()).isNull();
        assertThat(p.realizedPnl()).isEqualByComparingTo("2000");
    }

    @DisplayName("卖出超过持仓抛出异常")
    @Test
    void sellExceedingPositionThrows() {
        Position p = newPosition().applyBuy(bd("100"), bd("100"), bd("0"));
        PortfolioException ex = catchThrowableOfType(() -> p.applySell(bd("120"), bd("101"), bd("0")), PortfolioException.class);
        assertThat(ex).isNotNull().hasMessageContaining("卖出数量超过持仓");
        assertThat(ex.code()).isEqualTo(PortfolioErrorCode.SELL_EXCEEDS_QUANTITY);
    }

    @DisplayName("盈亏恒等式在非整数均价下仍成立")
    @Test
    void pnlIdentityHoldsWithNonIntegerAvgCost() {
        Position p = newPosition()
                .applyBuy(bd("10"), bd("3"), bd("0"))    // cost 30
                .applyCashDividend(bd("1"))              // costBasis 29
                .applySell(bd("10"), bd("1"), bd("0"));  // 均价 9.67
        assertThat(p.realizedPnl()).isEqualByComparingTo("0.3333");
        assertThat(p.costBasis().add(p.netCashFlow())).isEqualByComparingTo(p.realizedPnl());
    }

    @DisplayName("买入负价格被拒")
    @Test
    void negativeBuyPriceRejected() {
        Position p = newPosition();
        assertCode(() -> p.applyBuy(bd("-1"), bd("100"), bd("0")), PortfolioErrorCode.INVALID_INPUT);
    }

    @DisplayName("买入零数量被拒")
    @Test
    void zeroBuyQuantityRejected() {
        Position p = newPosition();
        assertCode(() -> p.applyBuy(bd("100"), bd("0"), bd("0")), PortfolioErrorCode.INVALID_INPUT);
    }

    @DisplayName("买入负手续费被拒")
    @Test
    void negativeBuyFeeRejected() {
        Position p = newPosition();
        assertCode(() -> p.applyBuy(bd("100"), bd("100"), bd("-1")), PortfolioErrorCode.INVALID_INPUT);
    }

    @DisplayName("卖出负数量被拒")
    @Test
    void negativeSellQuantityRejected() {
        Position p = newPosition().applyBuy(bd("100"), bd("100"), bd("0"));
        assertCode(() -> p.applySell(bd("120"), bd("-10"), bd("0")), PortfolioErrorCode.INVALID_INPUT);
    }

    @DisplayName("卖出负价格被拒")
    @Test
    void negativeSellPriceRejected() {
        Position p = newPosition().applyBuy(bd("100"), bd("100"), bd("0"));
        assertCode(() -> p.applySell(bd("-120"), bd("10"), bd("0")), PortfolioErrorCode.INVALID_INPUT);
    }

    @DisplayName("卖出负手续费被拒")
    @Test
    void negativeSellFeeRejected() {
        Position p = newPosition().applyBuy(bd("100"), bd("100"), bd("0"));
        assertCode(() -> p.applySell(bd("120"), bd("10"), bd("-1")), PortfolioErrorCode.INVALID_INPUT);
    }

    @DisplayName("现金分红为负被拒")
    @Test
    void negativeCashDividendRejected() {
        Position p = newPosition().applyBuy(bd("100"), bd("100"), bd("0"));
        assertCode(() -> p.applyCashDividend(bd("-1")), PortfolioErrorCode.INVALID_INPUT);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String code) {
        PortfolioException ex = catchThrowableOfType(callable, PortfolioException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo(code);
    }
}

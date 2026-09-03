package com.portfolio.invest.domain.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** 持仓聚合根 + 成本盈亏引擎（加权平均 + 分红降成本）。不可变，apply* 返回新实例。 */
public final class Position {

    private static final int SCALE = 4;
    private static final int PRICE_SCALE = 2;
    private static final RoundingMode RM = RoundingMode.HALF_UP;

    private final Long id;
    private final Long portfolioId;
    private final Long groupId;
    private final String stockCode;
    private final String stockName;
    private final BigDecimal quantity;
    private final BigDecimal costBasis;
    private final BigDecimal totalBuyCost;
    private final BigDecimal cumulativeCashDividend;
    private final BigDecimal realizedPnl;
    private final BigDecimal netCashFlow;
    private final Instant createdAt;
    private final Instant updatedAt;
    /** 乐观锁版本：新建为 null，从库载入后携带，整行 merge 写回时用于冲突检测。 */
    private final Long version;

    private Position(Long id, Long portfolioId, Long groupId, String stockCode, String stockName,
                     BigDecimal quantity, BigDecimal costBasis, BigDecimal totalBuyCost,
                     BigDecimal cumulativeCashDividend, BigDecimal realizedPnl, BigDecimal netCashFlow,
                     Instant createdAt, Instant updatedAt, Long version) {
        this.id = id;
        this.portfolioId = portfolioId;
        this.groupId = groupId;
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.quantity = quantity;
        this.costBasis = costBasis;
        this.totalBuyCost = totalBuyCost;
        this.cumulativeCashDividend = cumulativeCashDividend;
        this.realizedPnl = realizedPnl;
        this.netCashFlow = netCashFlow;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static Position create(Long portfolioId, Long groupId, String stockCode, String stockName, Instant now) {
        return new Position(null, portfolioId, groupId, stockCode, stockName,
                z(), z(), z(), z(), z(), z(), now, now, null);
    }

    public static Position reconstitute(Long id, Long portfolioId, Long groupId, String stockCode, String stockName,
                                        BigDecimal quantity, BigDecimal costBasis, BigDecimal totalBuyCost,
                                        BigDecimal cumulativeCashDividend, BigDecimal realizedPnl, BigDecimal netCashFlow,
                                        Instant createdAt, Instant updatedAt) {
        return reconstitute(id, portfolioId, groupId, stockCode, stockName,
                quantity, costBasis, totalBuyCost, cumulativeCashDividend, realizedPnl, netCashFlow,
                createdAt, updatedAt, null);
    }

    public static Position reconstitute(Long id, Long portfolioId, Long groupId, String stockCode, String stockName,
                                        BigDecimal quantity, BigDecimal costBasis, BigDecimal totalBuyCost,
                                        BigDecimal cumulativeCashDividend, BigDecimal realizedPnl, BigDecimal netCashFlow,
                                        Instant createdAt, Instant updatedAt, Long version) {
        return new Position(id, portfolioId, groupId, stockCode, stockName,
                quantity, costBasis, totalBuyCost, cumulativeCashDividend, realizedPnl, netCashFlow,
                createdAt, updatedAt, version);
    }

    /** 买入：数量、成本、现金净贡献更新；摊薄成本价随之变化。 */
    public Position applyBuy(BigDecimal price, BigDecimal qty, BigDecimal fee) {
        BigDecimal cost = price.multiply(qty).add(fee);
        return copy(quantity.add(qty), costBasis.add(cost), totalBuyCost.add(cost),
                cumulativeCashDividend, realizedPnl, netCashFlow.subtract(cost));
    }

    /** 卖出：按精确移除成本匹配，计算已实现收益与现金流入。 */
    public Position applySell(BigDecimal price, BigDecimal qty, BigDecimal fee) {
        if (qty.compareTo(quantity) > 0) {
            throw new PortfolioException("SELL_EXCEEDS_QUANTITY", "卖出数量超过持仓");
        }
        BigDecimal proceeds = price.multiply(qty).subtract(fee);
        BigDecimal remaining = quantity.subtract(qty);
        // 按比例扣减成本，保持摊薄成本价不变
        BigDecimal newCostBasis = remaining.signum() == 0
                ? z()
                : costBasis.multiply(remaining).divide(quantity, SCALE, RM);
        BigDecimal realized = proceeds.subtract(costBasis.subtract(newCostBasis));
        return copy(remaining, newCostBasis, totalBuyCost, cumulativeCashDividend,
                realizedPnl.add(realized), netCashFlow.add(proceeds));
    }

    /** 现金分红：降低摊薄成本，增加现金净贡献与累计分红。 */
    public Position applyCashDividend(BigDecimal totalAmount) {
        return copy(quantity, costBasis.subtract(totalAmount), totalBuyCost,
                cumulativeCashDividend.add(totalAmount), realizedPnl, netCashFlow.add(totalAmount));
    }

    /** 送股：按比例增股数，摊薄每股成本（成本总额不变）。 */
    public Position applyStockDividend(BigDecimal ratio) {
        BigDecimal bonus = quantity.multiply(ratio);
        return copy(quantity.add(bonus), costBasis, totalBuyCost, cumulativeCashDividend, realizedPnl, netCashFlow);
    }

    /** 摊薄成本价 = costBasis / quantity；无持仓时返回 null。 */
    public BigDecimal avgCost() {
        if (quantity.signum() == 0) {
            return null;
        }
        return costBasis.divide(quantity, PRICE_SCALE, RM);
    }

    private Position copy(BigDecimal newQuantity, BigDecimal newCostBasis, BigDecimal newTotalBuyCost,
                          BigDecimal newDividend, BigDecimal newRealized, BigDecimal newNetCashFlow) {
        return new Position(id, portfolioId, groupId, stockCode, stockName,
                newQuantity.setScale(SCALE, RM), newCostBasis.setScale(SCALE, RM),
                newTotalBuyCost.setScale(SCALE, RM), newDividend.setScale(SCALE, RM),
                newRealized.setScale(SCALE, RM), newNetCashFlow.setScale(SCALE, RM),
                createdAt, Instant.now(), version);
    }

    private static BigDecimal z() {
        return BigDecimal.ZERO.setScale(SCALE, RM);
    }

    public Long id() { return id; }
    public Long portfolioId() { return portfolioId; }
    public Long groupId() { return groupId; }
    public String stockCode() { return stockCode; }
    public String stockName() { return stockName; }
    public BigDecimal quantity() { return quantity; }
    public BigDecimal costBasis() { return costBasis; }
    public BigDecimal totalBuyCost() { return totalBuyCost; }
    public BigDecimal cumulativeCashDividend() { return cumulativeCashDividend; }
    public BigDecimal realizedPnl() { return realizedPnl; }
    public BigDecimal netCashFlow() { return netCashFlow; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Long version() { return version; }
}

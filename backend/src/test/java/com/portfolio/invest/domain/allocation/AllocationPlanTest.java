package com.portfolio.invest.domain.allocation;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllocationPlanTest {

    private static Map<AssetClass, BigDecimal> weights60_40() {
        return Map.of(AssetClass.STOCK, new BigDecimal("60"), AssetClass.BOND, new BigDecimal("40"));
    }

    @Test
    void 创建方案权重合法() {
        var p = AllocationPlan.create(1L, "平衡", PlanSource.CUSTOM, weights60_40(), Instant.now());
        assertThat(p.name()).isEqualTo("平衡");
        assertThat(p.active()).isFalse();
        assertThat(p.weights().get(AssetClass.STOCK)).isEqualByComparingTo("60");
    }

    @Test
    void 权重之和不为100抛异常() {
        var bad = Map.of(AssetClass.STOCK, new BigDecimal("60"), AssetClass.BOND, new BigDecimal("30"));
        assertThatThrownBy(() -> AllocationPlan.create(1L, "坏", PlanSource.CUSTOM, bad, Instant.now()))
                .isInstanceOfSatisfying(AllocationException.class,
                        e -> assertThat(e.code()).isEqualTo(AllocationErrorCode.INVALID_WEIGHTS));
    }

    @Test
    void 权重和接近100在容差内被接受() {
        // DB NUMERIC(18,4) 四舍五入后合法读回值如 33.3333×3=99.9999，精确比对会误拒
        var close = Map.of(AssetClass.STOCK, new BigDecimal("33.3333"),
                AssetClass.BOND, new BigDecimal("33.3333"),
                AssetClass.CASH, new BigDecimal("33.3333"));
        assertThat(AllocationPlan.create(1L, "三等分", PlanSource.CUSTOM, close, Instant.now()).weights()).hasSize(3);
    }

    @Test
    void 权重和明显偏离100仍被拒() {
        // 90 与 100 相差 10，远超容差
        var bad = Map.of(AssetClass.STOCK, new BigDecimal("60"), AssetClass.BOND, new BigDecimal("30"));
        assertThatThrownBy(() -> AllocationPlan.create(1L, "坏", PlanSource.CUSTOM, bad, Instant.now()))
                .isInstanceOfSatisfying(AllocationException.class,
                        e -> assertThat(e.code()).isEqualTo(AllocationErrorCode.INVALID_WEIGHTS));
    }

    @Test
    void 权重为负抛异常() {
        var neg = Map.of(AssetClass.STOCK, new BigDecimal("-10"), AssetClass.BOND, new BigDecimal("110"));
        assertThatThrownBy(() -> AllocationPlan.create(1L, "负", PlanSource.CUSTOM, neg, Instant.now()))
                .isInstanceOfSatisfying(AllocationException.class,
                        e -> assertThat(e.code()).isEqualTo(AllocationErrorCode.INVALID_WEIGHTS));
    }

    @Test
    void 改名与改权重返回新实例() {
        var p = AllocationPlan.create(1L, "平衡", PlanSource.TEMPLATE, weights60_40(), Instant.now());
        var renamed = p.rename("稳健");
        assertThat(renamed.name()).isEqualTo("稳健");
        assertThat(p.name()).isEqualTo("平衡"); // 原实例不变
        var updated = renamed.updateWeights(Map.of(AssetClass.STOCK, new BigDecimal("40"),
                AssetClass.BOND, new BigDecimal("60")));
        assertThat(updated.weights().get(AssetClass.BOND)).isEqualByComparingTo("60");
    }

    @Test
    void 激活与停用() {
        var p = AllocationPlan.create(1L, "平衡", PlanSource.CUSTOM, weights60_40(), Instant.now());
        assertThat(p.activate().active()).isTrue();
        assertThat(p.activate().deactivate().active()).isFalse();
    }
}

package com.portfolio.invest.application.allocation;

import com.portfolio.invest.application.portfolio.AssetAllocationView;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.domain.allocation.AllocationErrorCode;
import com.portfolio.invest.domain.allocation.AllocationException;
import com.portfolio.invest.domain.allocation.AllocationPlan;
import com.portfolio.invest.domain.allocation.AllocationPlanRepository;
import com.portfolio.invest.domain.allocation.AssetClass;
import com.portfolio.invest.domain.allocation.PlanSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AllocationApplicationServiceTest {

    private final AllocationPlanRepository repo = mock(AllocationPlanRepository.class);
    private final PortfolioApplicationService portfolio = mock(PortfolioApplicationService.class);
    private AllocationApplicationService service;

    @BeforeEach
    void setUp() {
        service = new AllocationApplicationService(repo, portfolio);
    }

    private static List<WeightInput> w60_40() {
        return List.of(new WeightInput(AssetClass.STOCK, new BigDecimal("60")),
                new WeightInput(AssetClass.BOND, new BigDecimal("40")));
    }

    private static AllocationPlan activePlan() {
        return AllocationPlan.reconstitute(10L, 1L, "平衡", PlanSource.TEMPLATE,
                Map.of(AssetClass.STOCK, new BigDecimal("60"), AssetClass.BOND, new BigDecimal("40")),
                true, Instant.now(), Instant.now());
    }

    @Test
    void 模板列表返回四个() {
        assertThat(service.templates()).hasSize(4);
        assertThat(service.templates().get(0).name()).isEqualTo("60/40 股债平衡");
    }

    @Test
    void 创建方案保存并返回() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var view = service.createPlan(1L, new CreatePlanCommand("平衡", PlanSource.TEMPLATE, w60_40()));
        assertThat(view.name()).isEqualTo("平衡");
        assertThat(view.source()).isEqualTo(PlanSource.TEMPLATE);
        assertThat(view.weights()).hasSize(2);
    }

    @Test
    void 权重重复抛INVALID_INPUT() {
        var dup = List.of(new WeightInput(AssetClass.STOCK, new BigDecimal("60")),
                new WeightInput(AssetClass.STOCK, new BigDecimal("40")));
        assertThatThrownBy(() -> service.createPlan(1L, new CreatePlanCommand("x", PlanSource.CUSTOM, dup)))
                .isInstanceOfSatisfying(AllocationException.class,
                        e -> assertThat(e.code()).isEqualTo(AllocationErrorCode.INVALID_INPUT));
    }

    @Test
    void 激活方案先清空其余生效() {
        when(repo.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(activePlan()));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.activatePlan(1L, 10L);

        verify(repo).deactivateAllByUserId(1L);
    }

    @Test
    void 更新方案改名与改权重() {
        when(repo.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(activePlan()));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.updatePlan(1L, 10L, new UpdatePlanCommand("稳健", List.of(
                new WeightInput(AssetClass.STOCK, new BigDecimal("40")),
                new WeightInput(AssetClass.BOND, new BigDecimal("60")))));

        assertThat(view.name()).isEqualTo("稳健");
        assertThat(view.weights()).hasSize(2);
        assertThat(view.weights().get(0).weight()).isEqualByComparingTo("40");
    }

    @Test
    void 删除方案() {
        when(repo.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(activePlan()));
        service.deletePlan(1L, 10L);
        verify(repo).deleteById(10L);
    }

    @Test
    void 非本人方案抛NOT_FOUND() {
        when(repo.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deletePlan(1L, 99L))
                .isInstanceOfSatisfying(AllocationException.class,
                        e -> assertThat(e.code()).isEqualTo(AllocationErrorCode.NOT_FOUND));
    }

    @Test
    void 无生效方案偏离度为空() {
        when(repo.findActiveByUserId(1L)).thenReturn(Optional.empty());
        assertThat(service.deviation(1L).slices()).isEmpty();
    }

    @Test
    void 偏离度映射权益现金并计算差值() {
        when(repo.findActiveByUserId(1L)).thenReturn(Optional.of(activePlan()));
        when(portfolio.allocation(1L)).thenReturn(new AssetAllocationView(List.of(
                new AssetAllocationView.Slice("权益", new BigDecimal("12000"), new BigDecimal("70.59")),
                new AssetAllocationView.Slice("现金", new BigDecimal("5000"), new BigDecimal("29.41")))));

        var view = service.deviation(1L);

        assertThat(view.slices()).hasSize(5);
        var stock = view.slices().get(0);
        assertThat(stock.assetClass()).isEqualTo(AssetClass.STOCK);
        assertThat(stock.targetWeight()).isEqualByComparingTo("60");
        assertThat(stock.actualWeight()).isEqualByComparingTo("70.59");
        assertThat(stock.deviation()).isEqualByComparingTo("10.59");
        var bond = view.slices().get(1);
        assertThat(bond.targetWeight()).isEqualByComparingTo("40");
        assertThat(bond.actualWeight()).isEqualByComparingTo("0");
        assertThat(bond.deviation()).isEqualByComparingTo("-40");
    }
}

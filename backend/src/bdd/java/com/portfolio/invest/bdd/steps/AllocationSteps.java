package com.portfolio.invest.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.application.allocation.AllocationApplicationService;
import com.portfolio.invest.application.allocation.CreatePlanCommand;
import com.portfolio.invest.application.allocation.WeightInput;
import com.portfolio.invest.domain.allocation.AllocationTemplate;
import com.portfolio.invest.domain.allocation.AssetClass;
import com.portfolio.invest.domain.allocation.PlanSource;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;

public class AllocationSteps {

    @Autowired
    AllocationApplicationService allocationService;

    @Autowired
    ScenarioContext ctx;

    @当("套用模板 {string} 创建方案 {string}")
    public void 套用模板(String templateName, String planName) {
        AllocationTemplate t = List.of(AllocationTemplate.values()).stream()
                .filter(x -> x.displayName().equals(templateName))
                .findFirst().orElseThrow();
        var weights = t.weights().entrySet().stream()
                .map(e -> new WeightInput(e.getKey(), e.getValue()))
                .toList();
        var view = allocationService.createPlan(ctx.getUserId(),
                new CreatePlanCommand(planName, PlanSource.TEMPLATE, weights));
        ctx.setPlanId(view.id());
    }

    @当("激活该方案")
    public void 激活() {
        allocationService.activatePlan(ctx.getUserId(), ctx.getPlanId());
    }

    @那么("该用户应有 {int} 个方案")
    public void 方案数(int count) {
        assertThat(allocationService.plans(ctx.getUserId())).hasSize(count);
    }

    @那么("生效方案的股票权重为 {bigdecimal}，债券权重为 {bigdecimal}")
    public void 生效权重(BigDecimal stock, BigDecimal bond) {
        var dev = allocationService.deviation(ctx.getUserId());
        var stockSlice = dev.slices().stream()
                .filter(s -> s.assetClass() == AssetClass.STOCK).findFirst().orElseThrow();
        var bondSlice = dev.slices().stream()
                .filter(s -> s.assetClass() == AssetClass.BOND).findFirst().orElseThrow();
        assertThat(stockSlice.targetWeight()).isEqualByComparingTo(stock);
        assertThat(bondSlice.targetWeight()).isEqualByComparingTo(bond);
    }

    @那么("偏离度应返回空")
    public void 偏离度空() {
        assertThat(allocationService.deviation(ctx.getUserId()).slices()).isEmpty();
    }

    @那么("偏离度应返回 {int} 类资产，股票目标权重为 {bigdecimal}")
    public void 偏离度五类(int size, BigDecimal stockTarget) {
        var dev = allocationService.deviation(ctx.getUserId());
        assertThat(dev.slices()).hasSize(size);
        var stockSlice = dev.slices().stream()
                .filter(s -> s.assetClass() == AssetClass.STOCK).findFirst().orElseThrow();
        assertThat(stockSlice.targetWeight()).isEqualByComparingTo(stockTarget);
    }
}

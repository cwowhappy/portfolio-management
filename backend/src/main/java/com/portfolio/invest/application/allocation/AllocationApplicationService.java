package com.portfolio.invest.application.allocation;

import com.portfolio.invest.application.portfolio.AssetAllocationView;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.domain.allocation.AllocationErrorCode;
import com.portfolio.invest.domain.allocation.AllocationException;
import com.portfolio.invest.domain.allocation.AllocationPlan;
import com.portfolio.invest.domain.allocation.AllocationPlanRepository;
import com.portfolio.invest.domain.allocation.AllocationTemplate;
import com.portfolio.invest.domain.allocation.AssetClass;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AllocationApplicationService {

    private final AllocationPlanRepository repository;
    private final PortfolioApplicationService portfolioService;

    public AllocationApplicationService(AllocationPlanRepository repository,
                                        PortfolioApplicationService portfolioService) {
        this.repository = repository;
        this.portfolioService = portfolioService;
    }

    public List<TemplateView> templates() {
        return Arrays.stream(AllocationTemplate.values()).map(TemplateView::from).toList();
    }

    public List<PlanView> plans(Long userId) {
        return repository.findByUserId(userId).stream().map(PlanView::from).toList();
    }

    @Transactional
    public PlanView createPlan(Long userId, CreatePlanCommand cmd) {
        AllocationPlan plan = AllocationPlan.create(userId, cmd.name().trim(), cmd.source(),
                toWeights(cmd.weights()), Instant.now());
        return PlanView.from(repository.save(plan));
    }

    @Transactional
    public PlanView updatePlan(Long userId, Long planId, UpdatePlanCommand cmd) {
        AllocationPlan plan = requirePlan(userId, planId)
                .rename(cmd.name().trim())
                .updateWeights(toWeights(cmd.weights()));
        return PlanView.from(repository.save(plan));
    }

    @Transactional
    public PlanView activatePlan(Long userId, Long planId) {
        AllocationPlan plan = requirePlan(userId, planId);
        repository.deactivateAllByUserId(userId);
        return PlanView.from(repository.save(plan.activate()));
    }

    @Transactional
    public void deletePlan(Long userId, Long planId) {
        requirePlan(userId, planId);
        repository.deleteById(planId);
    }

    public DeviationView deviation(Long userId) {
        var active = repository.findActiveByUserId(userId);
        if (active.isEmpty()) {
            return new DeviationView(List.of());
        }
        AllocationPlan plan = active.get();
        Map<AssetClass, BigDecimal> actual = mapHoldings(portfolioService.allocation(userId));
        List<DeviationView.DeviationSlice> slices = new ArrayList<>();
        for (AssetClass ac : AssetClass.values()) {
            BigDecimal target = plan.weights().getOrDefault(ac, BigDecimal.ZERO);
            BigDecimal actualWeight = actual.getOrDefault(ac, BigDecimal.ZERO);
            slices.add(new DeviationView.DeviationSlice(ac, target, actualWeight, actualWeight.subtract(target)));
        }
        return new DeviationView(slices);
    }

    private AllocationPlan requirePlan(Long userId, Long planId) {
        return repository.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new AllocationException(AllocationErrorCode.NOT_FOUND, "方案不存在"));
    }

    private Map<AssetClass, BigDecimal> toWeights(List<WeightInput> inputs) {
        Map<AssetClass, BigDecimal> m = new LinkedHashMap<>();
        for (var in : inputs) {
            if (m.putIfAbsent(in.assetClass(), in.weight()) != null) {
                throw new AllocationException(AllocationErrorCode.INVALID_INPUT, "资产类别重复");
            }
        }
        return m;
    }

    /** 持仓侧只有「权益/现金」两片，映射到资产大类；其余类别在偏离度中记 0。 */
    private Map<AssetClass, BigDecimal> mapHoldings(AssetAllocationView view) {
        Map<AssetClass, BigDecimal> m = new EnumMap<>(AssetClass.class);
        for (var slice : view.slices()) {
            AssetClass ac = switch (slice.category()) {
                case "权益" -> AssetClass.STOCK;
                case "现金" -> AssetClass.CASH;
                default -> null;
            };
            if (ac != null) {
                m.put(ac, slice.ratio());
            }
        }
        return m;
    }
}

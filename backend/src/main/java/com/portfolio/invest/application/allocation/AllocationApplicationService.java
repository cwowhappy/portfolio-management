package com.portfolio.invest.application.allocation;

import com.portfolio.invest.application.portfolio.AllocationSliceCategory;
import com.portfolio.invest.application.portfolio.AssetAllocationView;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.domain.allocation.AllocationErrorCode;
import com.portfolio.invest.domain.allocation.AllocationException;
import com.portfolio.invest.domain.allocation.AllocationPlan;
import com.portfolio.invest.domain.allocation.AllocationPlanRepository;
import com.portfolio.invest.domain.allocation.AllocationTemplate;
import com.portfolio.invest.domain.allocation.AssetClass;
import com.portfolio.invest.domain.allocation.RebalanceCalculator;
import com.portfolio.invest.domain.allocation.RebalanceFrequency;
import com.portfolio.invest.domain.allocation.RiskAssessment;
import com.portfolio.invest.domain.allocation.RiskAssessmentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AllocationApplicationService {

    private final AllocationPlanRepository repository;
    private final PortfolioApplicationService portfolioService;
    private final RiskAssessmentRepository assessmentRepository;

    public AllocationApplicationService(AllocationPlanRepository repository,
                                        PortfolioApplicationService portfolioService,
                                        RiskAssessmentRepository assessmentRepository) {
        this.repository = repository;
        this.portfolioService = portfolioService;
        this.assessmentRepository = assessmentRepository;
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
                toWeights(cmd.weights()), normalizeFrequency(cmd.rebalanceFrequency()), Instant.now());
        return PlanView.from(repository.save(plan));
    }

    @Transactional
    public PlanView updatePlan(Long userId, Long planId, UpdatePlanCommand cmd) {
        AllocationPlan plan = requirePlan(userId, planId)
                .rename(cmd.name().trim())
                .updateWeights(toWeights(cmd.weights()))
                .withFrequency(normalizeFrequency(cmd.rebalanceFrequency()));
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

    /** 再平衡读侧计算：生效方案 × 持仓聚合 → 提醒状态 + 逐类买卖金额建议（页内卡与导航红点共用）。 */
    public RebalanceView rebalance(Long userId) {
        var active = repository.findActiveByUserId(userId);
        if (active.isEmpty()) {
            return RebalanceView.empty();
        }
        AllocationPlan plan = active.get();
        var view = portfolioService.allocation(userId);
        Map<AssetClass, BigDecimal> actual = mapHoldings(view);
        BigDecimal totalAssets = view.slices().stream()
                .map(AssetAllocationView.Slice::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var result = RebalanceCalculator.calculate(plan.weights(), actual, totalAssets,
                plan.rebalanceFrequency(), plan.lastRebalancedAt(), Instant.now());
        return RebalanceView.from(plan, result, totalAssets);
    }

    /** ack「已完成再平衡」：重置时间提醒锚点（幂等，重复 ack 仅刷新时间）。 */
    @Transactional
    public void acknowledgeRebalance(Long userId) {
        AllocationPlan plan = repository.findActiveByUserId(userId)
                .orElseThrow(() -> new AllocationException(AllocationErrorCode.NOT_FOUND, "无生效方案"));
        repository.save(plan.markRebalanced(Instant.now()));
    }

    public QuestionnaireView questionnaire() {
        return QuestionnaireView.fromBuiltin();
    }

    public Optional<AssessmentView> latestAssessment(Long userId) {
        return assessmentRepository.findByUserId(userId).map(AssessmentView::from);
    }

    @Transactional
    public AssessmentView submitAssessment(Long userId, SubmitAssessmentCommand cmd) {
        Map<String, String> answers = new LinkedHashMap<>();
        for (AnswerInput in : cmd.answers()) {
            if (answers.putIfAbsent(in.questionId(), in.optionId()) != null) {
                throw new AllocationException(AllocationErrorCode.INVALID_INPUT, "题目重复作答");
            }
        }
        RiskAssessment graded = RiskAssessment.grade(userId, answers, Instant.now());
        return AssessmentView.from(assessmentRepository.save(graded));
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

    /** 命令频率缺省（前端未传/存量调用）归一为 OFF。 */
    private static RebalanceFrequency normalizeFrequency(RebalanceFrequency frequency) {
        return frequency == null ? RebalanceFrequency.OFF : frequency;
    }

    /** 持仓侧只有「权益/现金」两片，映射到资产大类；其余类别在偏离度中记 0。 */
    private Map<AssetClass, BigDecimal> mapHoldings(AssetAllocationView view) {
        Map<AssetClass, BigDecimal> m = new EnumMap<>(AssetClass.class);
        for (var slice : view.slices()) {
            AssetClass ac = switch (slice.category()) {
                case AllocationSliceCategory.EQUITY -> AssetClass.STOCK;
                case AllocationSliceCategory.CASH -> AssetClass.CASH;
            };
            m.put(ac, slice.ratio());
        }
        return m;
    }
}

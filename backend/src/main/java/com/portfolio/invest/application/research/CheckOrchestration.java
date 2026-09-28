package com.portfolio.invest.application.research;

import com.portfolio.invest.application.alert.StockMetric;
import com.portfolio.invest.application.alert.ValuationDailyPort;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Position;
import com.portfolio.invest.domain.research.CheckContext;
import com.portfolio.invest.domain.research.EntryPlan;
import com.portfolio.invest.domain.research.Falsifier;
import com.portfolio.invest.domain.research.MarketSnapshot;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.RuleInput;
import com.portfolio.invest.domain.valuation.ValuationRepository;
import com.portfolio.invest.domain.wiki.PrincipleRule;
import com.portfolio.invest.domain.wiki.PrincipleRuleRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 纪律检查编排侧（P3-T4，NFR-4 跨域落点）：为 {@code DisciplineCheckService} 组装
 * {@link CheckContext} 与规则输入，不含事务与落库——纯读聚合，preview/submit 共用。
 *
 * <p>职责三件：
 * <ul>
 *   <li>PrincipleRule → {@link RuleInput} 转换（domain 不横向 import wiki；启用过滤在转换侧完成）</li>
 *   <li>计划占比聚合：planned 单票/行业 = 建仓计划 Σratio + 持仓现占比。现占比口径与
 *       {@code PrincipleAlertEvaluator} 一致——分母 Σ(close×quantity) 有价持仓（收盘价取
 *       stock_valuation_daily 当日快照，无收盘价票全维度跳过 fail-safe）；行业归属经申万映射，
 *       未映射票只进分母（行业占比不可得 → null，检查条目跳过不冒充 UNSET）</li>
 *   <li>f01 勾选集与证伪条件集透传（null 容错由 CheckContext 承担）</li>
 * </ul>
 */
@Service
public class CheckOrchestration {

    /** 现占比除法 scale（照 PrincipleAlertEvaluator 口径）。 */
    private static final int RATIO_SCALE = 10;

    private final PortfolioRepository portfolioRepository;
    private final ValuationRepository valuationRepository;
    private final ValuationDailyPort valuationDaily;
    private final PrincipleRuleRepository ruleRepository;

    public CheckOrchestration(PortfolioRepository portfolioRepository,
                              ValuationRepository valuationRepository,
                              ValuationDailyPort valuationDaily,
                              PrincipleRuleRepository ruleRepository) {
        this.portfolioRepository = portfolioRepository;
        this.valuationRepository = valuationRepository;
        this.valuationDaily = valuationDaily;
        this.ruleRepository = ruleRepository;
    }

    /** 启用中的纪律规则 → RuleInput（metric 取 PrincipleMetric 枚举名，与检查服务常量逐字一致）。 */
    public List<RuleInput> enabledRules(Long userId) {
        return ruleRepository.findByUserId(userId).stream()
                .filter(PrincipleRule::enabled)
                .map(rule -> new RuleInput(rule.metric().name(), rule.threshold()))
                .toList();
    }

    /** 建仓计划 Σratio（无计划 → null，SELL/REDUCE 语境仅检现占比）。 */
    public static BigDecimal planRatio(EntryPlan plan) {
        if (plan == null) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (var batch : plan.batches()) {
            sum = sum.add(batch.ratio());
        }
        return sum;
    }

    /**
     * 组装检查上下文：planRatio 携建仓/加减仓意图（可 null），快照提供 currentPe/Pb，
     * 持仓聚合提供现占比。
     */
    public CheckContext buildContext(ResearchProject project, BigDecimal planRatio,
                                     List<String> f01MustItems, List<Falsifier> falsifiers,
                                     MarketSnapshot snapshot) {
        PlannedRatios ratios = plannedRatios(project.userId(), project.stockCode(), planRatio);
        return new CheckContext(ratios.single(), ratios.industry(), snapshot.pe(), snapshot.pb(),
                f01MustItems, falsifiers);
    }

    private PlannedRatios plannedRatios(Long userId, String stockCode, BigDecimal planRatio) {
        List<Position> positions = portfolioRepository.findPortfolioByUserId(userId)
                .map(portfolio -> portfolioRepository.findPositionsByPortfolioId(portfolio.id()).stream()
                        .filter(pos -> pos.quantity().signum() > 0)
                        .toList())
                .orElse(List.of());
        if (positions.isEmpty()) {
            return new PlannedRatios(planRatio, planRatio);
        }
        List<String> codes = positions.stream().map(Position::stockCode).distinct().toList();
        Map<String, StockMetric> metrics = valuationDaily.latestTradingDay()
                .map(day -> valuationDaily.snapshots(day, codes))
                .orElse(Map.of());
        Map<String, BigDecimal> marketValues = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Position pos : positions) {
            StockMetric metric = metrics.get(pos.stockCode());
            if (metric == null || metric.close() == null) {
                continue; // 无当日收盘价：该票全维度跳过（fail-safe，同预警口径）
            }
            BigDecimal value = metric.close().multiply(pos.quantity());
            marketValues.merge(pos.stockCode(), value, BigDecimal::add);
            total = total.add(value);
        }
        if (total.signum() <= 0) {
            return new PlannedRatios(planRatio, planRatio); // 持仓全部无收盘价：现占比不可得
        }
        BigDecimal single = add(planRatio, marketValues.getOrDefault(stockCode, BigDecimal.ZERO)
                .divide(total, RATIO_SCALE, RoundingMode.HALF_UP));
        BigDecimal industry = industryRatio(stockCode, planRatio, marketValues, total);
        return new PlannedRatios(single, industry);
    }

    /** 行业占比：目标票未映射 → null（条目跳过）；已映射 → 同行业市值合计/total + 计划占比。 */
    private BigDecimal industryRatio(String stockCode, BigDecimal planRatio,
                                     Map<String, BigDecimal> marketValues, BigDecimal total) {
        Map<String, String> industries = valuationRepository.findAllIndustryMappings().stream()
                .collect(java.util.stream.Collectors.toMap(
                        mapping -> mapping.stockCode(), mapping -> mapping.industryName(), (a, b) -> a));
        String industry = industries.get(stockCode);
        if (industry == null) {
            return null;
        }
        BigDecimal industryValue = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : marketValues.entrySet()) {
            if (industry.equals(industries.get(entry.getKey()))) {
                industryValue = industryValue.add(entry.getValue());
            }
        }
        return add(planRatio, industryValue.divide(total, RATIO_SCALE, RoundingMode.HALF_UP));
    }

    private static BigDecimal add(BigDecimal planRatio, BigDecimal existing) {
        return (planRatio == null ? BigDecimal.ZERO : planRatio)
                .add(existing == null ? BigDecimal.ZERO : existing);
    }

    /** 计划后单票/行业占比（null=不可得，对应规则条目跳过）。 */
    private record PlannedRatios(BigDecimal single, BigDecimal industry) {}
}

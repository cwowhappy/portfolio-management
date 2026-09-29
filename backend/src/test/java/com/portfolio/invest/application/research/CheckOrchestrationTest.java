package com.portfolio.invest.application.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.alert.StockMetric;
import com.portfolio.invest.application.alert.ValuationDailyPort;
import com.portfolio.invest.domain.portfolio.CostMethod;
import com.portfolio.invest.domain.portfolio.Portfolio;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Position;
import com.portfolio.invest.domain.research.CheckContext;
import com.portfolio.invest.domain.research.MarketSnapshot;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.RuleInput;
import com.portfolio.invest.domain.valuation.ShenwanIndustryMapping;
import com.portfolio.invest.domain.valuation.ValuationRepository;
import com.portfolio.invest.domain.wiki.PrincipleMetric;
import com.portfolio.invest.domain.wiki.PrincipleRule;
import com.portfolio.invest.domain.wiki.PrincipleRuleRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 检查编排切片：PrincipleRule→RuleInput 转换（NFR-4）+ CheckContext 计划占比组装（持仓聚合口径）。 */
class CheckOrchestrationTest {

    private static final Instant NOW = Instant.parse("2026-09-28T08:00:00Z");
    private static final LocalDate TRADING_DAY = LocalDate.of(2026, 9, 28);

    private final PortfolioRepository portfolioRepository = mock(PortfolioRepository.class);
    private final ValuationRepository valuationRepository = mock(ValuationRepository.class);
    private final ValuationDailyPort valuationDaily = mock(ValuationDailyPort.class);
    private final PrincipleRuleRepository ruleRepository = mock(PrincipleRuleRepository.class);

    private CheckOrchestration orchestration;

    @BeforeEach
    void setUp() {
        orchestration = new CheckOrchestration(portfolioRepository, valuationRepository,
                valuationDaily, ruleRepository);
    }

    private static ResearchProject project() {
        return ResearchProject.reconstitute(5L, 1L, "600519", "贵州茅台", "801120",
                "茅台扩产研究", com.portfolio.invest.domain.research.ResearchStage.POSITION,
                com.portfolio.invest.domain.research.ProjectStatus.ACTIVE, 0L, NOW, NOW);
    }

    private static Position position(String code, String qty) {
        return Position.reconstitute(1L, 2L, 3L, code, code, new BigDecimal(qty), new BigDecimal("10"),
                new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, NOW, NOW);
    }

    @DisplayName("规则转换：只含启用规则，metric 与 PrincipleMetric 枚举名逐字一致（NFR-4 落点）")
    @Test
    void givenRules_whenEnabledRules_thenOnlyEnabledMappedByEnumName() {
        when(ruleRepository.findByUserId(1L)).thenReturn(List.of(
                PrincipleRule.reconstitute(1L, 1L, PrincipleMetric.SINGLE_POSITION_RATIO,
                        new BigDecimal("0.5"), true, null, NOW, NOW, 0L),
                PrincipleRule.reconstitute(2L, 1L, PrincipleMetric.STOCK_PE_MAX,
                        new BigDecimal("30"), false, "停用", NOW, NOW, 0L),
                PrincipleRule.reconstitute(3L, 1L, PrincipleMetric.STOCK_PB_MAX,
                        new BigDecimal("8"), true, null, NOW, NOW, 0L)));

        List<RuleInput> rules = orchestration.enabledRules(1L);

        assertThat(rules).containsExactly(
                new RuleInput("SINGLE_POSITION_RATIO", new BigDecimal("0.5")),
                new RuleInput("STOCK_PB_MAX", new BigDecimal("8")));
    }

    @DisplayName("无持仓：计划占比直传（single=industry=Σratio），快照估值透传")
    @Test
    void givenNoHoldings_whenBuildContext_thenPlanRatioPassedThrough() {
        when(portfolioRepository.findPortfolioByUserId(1L)).thenReturn(Optional.empty());
        MarketSnapshot snapshot = new MarketSnapshot(new BigDecimal("12.34"), new BigDecimal("25.5"),
                new BigDecimal("8.2"), "东财收盘及估值 2026-09-28");

        CheckContext ctx = orchestration.buildContext(project(), new BigDecimal("0.3"),
                List.of("能力圈"), List.of(), snapshot);

        assertThat(ctx.plannedSingleRatio()).isEqualByComparingTo("0.3");
        assertThat(ctx.plannedIndustryRatio()).isEqualByComparingTo("0.3");
        assertThat(ctx.currentPe()).isEqualByComparingTo("25.5");
        assertThat(ctx.currentPb()).isEqualByComparingTo("8.2");
        assertThat(ctx.f01MustItems()).containsExactly("能力圈");
        assertThat(ctx.falsifiers()).isEmpty();
    }

    @DisplayName("有持仓：计划占比 = Σratio + 现占比（分母 Σclose×qty，同 PrincipleAlertEvaluator 口径）")
    @Test
    void givenHoldings_whenBuildContext_thenPlanPlusExistingRatios() {
        stubPortfolio(position("600519", "100"), position("000858", "100"));
        stubSnapshots(Map.of(
                "600519", new StockMetric("600519", "贵州茅台", new BigDecimal("10"), null, null),
                "000858", new StockMetric("000858", "五粮液", new BigDecimal("10"), null, null)));
        stubIndustries(Map.of("600519", "白酒", "000858", "白酒"));
        // 目标 600519 现占比 1000/2000=0.5、行业（白酒两票合计）占比 1.0；计划 +0.3 → 0.8 / 1.3

        CheckContext ctx = orchestration.buildContext(project(), new BigDecimal("0.3"),
                List.of(), List.of(), new MarketSnapshot(null, null, null, null));

        assertThat(ctx.plannedSingleRatio()).isEqualByComparingTo("0.8");
        assertThat(ctx.plannedIndustryRatio()).isEqualByComparingTo("1.3");
    }

    @DisplayName("目标票未持有：现单票占比 0，行业仍聚合同行业持仓")
    @Test
    void givenTargetNotHeld_whenBuildContext_thenSingleIsPlanOnlyIndustryAggregated() {
        stubPortfolio(position("000858", "100"));
        stubSnapshots(Map.of("000858",
                new StockMetric("000858", "五粮液", new BigDecimal("10"), null, null)));
        stubIndustries(Map.of("000858", "白酒", "600519", "白酒"));

        CheckContext ctx = orchestration.buildContext(project(), new BigDecimal("0.3"),
                List.of(), List.of(), new MarketSnapshot(null, null, null, null));

        assertThat(ctx.plannedSingleRatio()).isEqualByComparingTo("0.3"); // 0 + 计划
        assertThat(ctx.plannedIndustryRatio()).isEqualByComparingTo("1.3"); // 同行业现占比 1.0 + 计划
    }

    @DisplayName("目标票行业未映射：industry=null（条目跳过，同预警「只进分母」口径），single 正常")
    @Test
    void givenUnmappedTargetIndustry_whenBuildContext_thenIndustryNullButSingleComputed() {
        stubPortfolio(position("600519", "100"), position("000858", "100"));
        stubSnapshots(Map.of(
                "600519", new StockMetric("600519", "贵州茅台", new BigDecimal("10"), null, null),
                "000858", new StockMetric("000858", "五粮液", new BigDecimal("10"), null, null)));
        stubIndustries(Map.of("000858", "白酒")); // 600519 无映射

        CheckContext ctx = orchestration.buildContext(project(), new BigDecimal("0.3"),
                List.of(), List.of(), new MarketSnapshot(null, null, null, null));

        assertThat(ctx.plannedSingleRatio()).isEqualByComparingTo("0.8");
        assertThat(ctx.plannedIndustryRatio()).isNull(); // 未映射 → 不可得 → 跳过条目
    }

    @DisplayName("持仓全部无当日收盘价：现占比不可得 → 仅计划占比（fail-safe 不判罚）")
    @Test
    void givenHoldingsWithoutCloses_whenBuildContext_thenPlanRatioOnly() {
        stubPortfolio(position("600519", "100"));
        stubSnapshots(Map.of()); // 无当日快照

        CheckContext ctx = orchestration.buildContext(project(), new BigDecimal("0.3"),
                List.of(), List.of(), new MarketSnapshot(null, null, null, null));

        assertThat(ctx.plannedSingleRatio()).isEqualByComparingTo("0.3");
        assertThat(ctx.plannedIndustryRatio()).isEqualByComparingTo("0.3");
    }

    @DisplayName("无计划且无持仓：占比 null（规则在而值缺 → 条目跳过，不冒充 UNSET）")
    @Test
    void givenNoPlanNoHoldings_whenBuildContext_thenRatiosNull() {
        when(portfolioRepository.findPortfolioByUserId(1L)).thenReturn(Optional.empty());

        CheckContext ctx = orchestration.buildContext(project(), null,
                List.of(), List.of(), new MarketSnapshot(null, null, null, null));

        assertThat(ctx.plannedSingleRatio()).isNull();
        assertThat(ctx.plannedIndustryRatio()).isNull();
    }

    @DisplayName("无计划但有持仓：现占比即检查值（SELL/REDUCE 语境）")
    @Test
    void givenNoPlanButHoldings_whenBuildContext_thenExistingRatiosOnly() {
        stubPortfolio(position("600519", "100"));
        stubSnapshots(Map.of("600519",
                new StockMetric("600519", "贵州茅台", new BigDecimal("10"), null, null)));
        stubIndustries(Map.of("600519", "白酒"));

        CheckContext ctx = orchestration.buildContext(project(), null,
                List.of(), List.of(), new MarketSnapshot(null, null, null, null));

        assertThat(ctx.plannedSingleRatio()).isEqualByComparingTo("1");
        assertThat(ctx.plannedIndustryRatio()).isEqualByComparingTo("1");
    }

    @DisplayName("已清仓持仓（quantity=0）不计入聚合")
    @Test
    void givenClosedPositions_whenBuildContext_thenExcluded() {
        stubPortfolio(position("600519", "0"));
        stubSnapshots(Map.of("600519",
                new StockMetric("600519", "贵州茅台", new BigDecimal("10"), null, null)));
        stubIndustries(Map.of("600519", "白酒"));

        CheckContext ctx = orchestration.buildContext(project(), new BigDecimal("0.3"),
                List.of(), List.of(), new MarketSnapshot(null, null, null, null));

        assertThat(ctx.plannedSingleRatio()).isEqualByComparingTo("0.3");
    }

    @DisplayName("falsifiers null 容错（CheckContext 视同空集合）")
    @Test
    void givenNullFalsifiers_whenBuildContext_thenTolerated() {
        when(portfolioRepository.findPortfolioByUserId(1L)).thenReturn(Optional.empty());
        CheckContext ctx = orchestration.buildContext(project(), null, null, null,
                new MarketSnapshot(null, null, null, null));
        assertThat(ctx.falsifiers()).isEmpty();
        assertThat(ctx.f01MustItems()).isEmpty();
    }

    private void stubPortfolio(Position... positions) {
        Portfolio portfolio = Portfolio.reconstitute(2L, 1L, CostMethod.WEIGHTED_AVG, NOW, NOW);
        when(portfolioRepository.findPortfolioByUserId(1L)).thenReturn(Optional.of(portfolio));
        when(portfolioRepository.findPositionsByPortfolioId(2L)).thenReturn(List.of(positions));
    }

    private void stubSnapshots(Map<String, StockMetric> metrics) {
        when(valuationDaily.latestTradingDay()).thenReturn(Optional.of(TRADING_DAY));
        when(valuationDaily.snapshots(TRADING_DAY, List.of("600519", "000858"))).thenReturn(metrics);
        when(valuationDaily.snapshots(TRADING_DAY, List.of("600519"))).thenReturn(metrics);
        when(valuationDaily.snapshots(TRADING_DAY, List.of("000858"))).thenReturn(metrics);
    }

    private void stubIndustries(Map<String, String> codeToIndustry) {
        when(valuationRepository.findAllIndustryMappings()).thenReturn(codeToIndustry.entrySet().stream()
                .map(e -> new ShenwanIndustryMapping(e.getKey(), null, null, e.getValue()))
                .toList());
    }
}

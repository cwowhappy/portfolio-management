package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.application.allocation.AllocationApplicationService;
import com.portfolio.invest.application.portfolio.AssetAllocationView;
import com.portfolio.invest.application.portfolio.ConcentrationView;
import com.portfolio.invest.application.portfolio.IndustryDistributionView;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.application.portfolio.PortfolioOverviewView;
import com.portfolio.invest.application.portfolio.AllocationSliceCategory;
import io.agentscope.core.message.ToolResultBlock;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserInvestToolsTest {

    private final PortfolioApplicationService portfolio = mock(PortfolioApplicationService.class);
    private final AllocationApplicationService allocation = mock(AllocationApplicationService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final UserInvestTools tools = new UserInvestTools(7L, portfolio, allocation, mapper);

    @DisplayName("analyze_portfolio：emit 饼图，摘要含总资产/集中度；userId 从构造器传导")
    @Test
    void givenHoldings_whenAnalyzePortfolio_thenEmitsPieAndSummary() {
        when(portfolio.overview(7L)).thenReturn(new PortfolioOverviewView(
                new BigDecimal("1.25E11"), new BigDecimal("1.0E11"), new BigDecimal("2.5E10"),
                null, null, null, 5, 2));
        when(portfolio.allocation(7L)).thenReturn(new AssetAllocationView(List.of(
                new AssetAllocationView.Slice(AllocationSliceCategory.EQUITY,
                        new BigDecimal("1.0E11"), new BigDecimal("0.8")))));
        when(portfolio.concentration(7L)).thenReturn(new ConcentrationView(
                List.of(new ConcentrationView.Holding("600519", "贵州茅台",
                        new BigDecimal("4.0E10"), new BigDecimal("32.0"))),
                new BigDecimal("68.0")));
        when(portfolio.industryDistribution(7L)).thenReturn(new IndustryDistributionView(List.of()));
        ToolResultBlock[] emitted = new ToolResultBlock[1];

        ToolResultBlock out = tools.analyzePortfolio(block -> emitted[0] = block);

        assertThat(emitted[0]).isNotNull();
        assertThat(emitted[0].getOutput().get(0).toString()).contains("\"type\":\"pie\"");
        assertThat(out.getOutput().get(0).toString()).contains("1250.0 亿").contains("68.0%");
        verify(portfolio).overview(7L); // userId 归属传导
    }

    @DisplayName("空持仓：不 emit，返回引导摘要")
    @Test
    void givenEmptyPortfolio_whenAnalyzePortfolio_thenNoEmitGuidance() {
        when(portfolio.overview(7L)).thenReturn(new PortfolioOverviewView(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, 0, 0));
        ToolResultBlock[] emitted = new ToolResultBlock[1];

        ToolResultBlock out = tools.analyzePortfolio(block -> emitted[0] = block);

        assertThat(emitted[0]).isNull();
        assertThat(out.getOutput().get(0).toString()).contains("暂无持仓");
    }

    @DisplayName("服务异常：不 emit，返回错误 JSON")
    @Test
    void givenServiceError_whenAnalyzePortfolio_thenErrorJson() {
        when(portfolio.overview(anyLong())).thenThrow(new IllegalStateException("db down"));
        ToolResultBlock[] emitted = new ToolResultBlock[1];

        ToolResultBlock out = tools.analyzePortfolio(block -> emitted[0] = block);

        assertThat(emitted[0]).isNull();
        assertThat(out.getOutput().get(0).toString()).contains("\"error\"");
    }

    @DisplayName("suggest_allocation 有测评无方案：emit 推荐对照表（刻度=服务层百分数）")
    @Test
    void givenAssessment_whenSuggestAllocation_thenEmitsDeviationTable() {
        when(allocation.latestAssessment(7L)).thenReturn(java.util.Optional.of(
                new com.portfolio.invest.application.allocation.AssessmentView(
                        62, com.portfolio.invest.domain.allocation.RiskProfile.BALANCED, "平衡",
                        List.of(new com.portfolio.invest.application.allocation.WeightView(
                                        com.portfolio.invest.domain.allocation.AssetClass.STOCK,
                                        new BigDecimal("60")),
                                new com.portfolio.invest.application.allocation.WeightView(
                                        com.portfolio.invest.domain.allocation.AssetClass.CASH,
                                        new BigDecimal("40"))),
                        java.util.Map.of(), java.time.Instant.parse("2026-09-01T00:00:00Z"))));
        when(portfolio.allocation(7L)).thenReturn(new AssetAllocationView(List.of(
                new AssetAllocationView.Slice(AllocationSliceCategory.EQUITY,
                        new BigDecimal("9.0E10"), new BigDecimal("90.0")))));
        when(allocation.deviation(7L)).thenReturn(new com.portfolio.invest.application.allocation.DeviationView(
                List.of()));
        ToolResultBlock[] emitted = new ToolResultBlock[1];

        ToolResultBlock out = tools.suggestAllocation(block -> emitted[0] = block);

        assertThat(emitted[0]).isNotNull();
        assertThat(emitted[0].getOutput().get(0).toString())
                .contains("推荐配置").contains("90.0").doesNotContain("9000"); // 百分数不二次放大
        assertThat(out.getOutput().get(0).toString()).contains("平衡").contains("62");
    }

    @DisplayName("suggest_allocation 有生效方案：DeviationView 直生成对照表，不再读持仓分布")
    @Test
    void givenActivePlan_whenSuggestAllocation_thenUsesDeviationWithoutRereadingPortfolio() {
        when(allocation.latestAssessment(7L)).thenReturn(java.util.Optional.of(
                new com.portfolio.invest.application.allocation.AssessmentView(
                        62, com.portfolio.invest.domain.allocation.RiskProfile.BALANCED, "平衡",
                        List.of(), java.util.Map.of(), java.time.Instant.parse("2026-09-01T00:00:00Z"))));
        when(allocation.deviation(7L)).thenReturn(new com.portfolio.invest.application.allocation.DeviationView(
                List.of(new com.portfolio.invest.application.allocation.DeviationView.DeviationSlice(
                        com.portfolio.invest.domain.allocation.AssetClass.STOCK,
                        new BigDecimal("60"), new BigDecimal("90"), new BigDecimal("30")))));
        ToolResultBlock[] emitted = new ToolResultBlock[1];

        ToolResultBlock out = tools.suggestAllocation(block -> emitted[0] = block);

        assertThat(emitted[0]).isNotNull();
        assertThat(emitted[0].getOutput().get(0).toString()).contains("配置方案 vs 当前").contains("30.0");
        assertThat(out.getOutput().get(0).toString()).contains("股票 +30.0pp");
        org.mockito.Mockito.verify(portfolio, org.mockito.Mockito.never()).allocation(anyLong()); // 消除重复全量读取
    }

    @DisplayName("suggest_allocation 无测评：不 emit，返回先完成测评引导")
    @Test
    void givenNoAssessment_whenSuggestAllocation_thenGuidance() {
        when(allocation.latestAssessment(7L)).thenReturn(java.util.Optional.empty());
        ToolResultBlock[] emitted = new ToolResultBlock[1];

        ToolResultBlock out = tools.suggestAllocation(block -> emitted[0] = block);

        assertThat(emitted[0]).isNull();
        assertThat(out.getOutput().get(0).toString()).contains("风险测评");
    }
}

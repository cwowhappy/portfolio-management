package com.portfolio.invest.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.chart.ChartSpecs;
import com.portfolio.invest.application.allocation.AllocationApplicationService;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用户态投研工具（F09/F10）：userId 经 UserToolkitFactory 构造注入，不进 LLM 上下文（@ToolParam 永不出现 userId）。
 * 非 Spring bean（per-会话实例）；错误兜底沿用 InvestTools.runBlock 模式（private 不可共享，此处复制）。
 */
public class UserInvestTools {

    private static final Logger log = LoggerFactory.getLogger(UserInvestTools.class);

    private final Long userId;
    private final PortfolioApplicationService portfolio;
    private final AllocationApplicationService allocation;
    private final ObjectMapper mapper;

    public UserInvestTools(Long userId, PortfolioApplicationService portfolio,
                           AllocationApplicationService allocation, ObjectMapper mapper) {
        this.userId = userId;
        this.portfolio = portfolio;
        this.allocation = allocation;
        this.mapper = mapper;
    }

    @Tool(
            name = "analyze_portfolio",
            description = "读取当前用户持仓组合：总览（总资产/成本/浮动盈亏/持仓数）、资产配置饼图、行业分布、集中度（前五大占比）。用户问「我的持仓/组合怎么样、仓位结构如何」时调用。数据为用户私有，仅本人可见。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock analyzePortfolio(ToolEmitter emitter) {
        return runBlock(() -> {
            var overview = portfolio.overview(userId);
            if (overview.positionCount() == 0) {
                return ToolResultBlock.text("暂无持仓。可到持仓页添加，或让我介绍怎么开始建立组合。");
            }
            var assetAllocation = portfolio.allocation(userId);
            var concentration = portfolio.concentration(userId);
            var industryDistribution = portfolio.industryDistribution(userId);
            emitter.emit(ToolResultBlock.builder()
                    .output(TextBlock.builder().text(mapper.writeValueAsString(
                            ChartSpecs.portfolioPie(assetAllocation))).build())
                    .build());
            return ToolResultBlock.text(
                    ChartSpecs.portfolioSummary(overview, concentration, industryDistribution));
        });
    }

    @Tool(
            name = "suggest_allocation",
            description = "资产配置建议：读取用户风险测评结果（无则引导先完成测评），给出推荐配置（保守/平衡/进取三档内置权重）与当前资产分布对照表及偏离。用户问「我该怎么配置/建议仓位/资产怎么配」时调用。推荐权重来自 M07 问卷评分模型，非本工具编造。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock suggestAllocation(ToolEmitter emitter) {
        return runBlock(() -> {
            var assessment = allocation.latestAssessment(userId);
            if (assessment.isEmpty()) {
                return ToolResultBlock.text(
                        "尚未完成风险测评。请先到配置页完成 M07 风险测评问卷，我再基于你的风险画像给出配置建议（不凭空编造建议）。");
            }
            var a = assessment.get();
            var current = portfolio.allocation(userId);
            var deviation = allocation.deviation(userId);
            emitter.emit(ToolResultBlock.builder()
                    .output(TextBlock.builder().text(mapper.writeValueAsString(
                            ChartSpecs.allocationDeviationTable(a.profileName(), a.weights(), current))).build())
                    .build());
            return ToolResultBlock.text(ChartSpecs.allocationSummary(a, deviation));
        });
    }

    /** 与 InvestTools 同款兜底：失败不 emit，返回错误 JSON 文本（前端 ChartCard 嗅探降级）。 */
    private ToolResultBlock runBlock(BlockSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.error("用户态工具执行异常 userId={}", userId, e);
            return ToolResultBlock.text(toError("工具执行失败", "请稍后重试"));
        }
    }

    private String toError(String message, String hint) {
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("error", message);
            body.put("hint", hint);
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            return "{\"error\":\"工具执行失败\",\"hint\":\"请稍后重试\"}";
        }
    }

    @FunctionalInterface
    private interface BlockSupplier {
        ToolResultBlock get() throws Exception;
    }
}

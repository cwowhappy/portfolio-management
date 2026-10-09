package com.portfolio.invest.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.application.allocation.AllocationApplicationService;
import com.portfolio.invest.application.eval.AgentPromptAssetPort;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.domain.eval.PromptAssetVersion;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 内置提示词资产采集器（MS-30 B3，设计规格 §4.1）：新建裸 Toolkit 枚举系统提示词 + 全部
 * 内置 @Tool 描述，供 PromptVersionRegistrar 启动对账 hash 登记。与
 * {@link UserToolkitFactory#build} 同源两件（InvestTools + UserInvestTools），但**不走
 * decorateWithRecording**——装饰后 getTool 返回 decorator（描述虽透传但取自 builderFrom
 * 重建，取原对象更稳），也不做 MCP 装配（版本登记只关心内置资产，MCP 工具描述属外部
 * 数据源不入版本链）。
 *
 * <p>@Tool 描述是注解常量、与实例状态无关：UserInvestTools 的 userId 以 0 占位（描述采集
 * 不触发任何工具调用）；键词表与 eval 侧 EvalAssetHasher 一致（system.invest /
 * tool.&lt;name&gt;），保证 eval 报告 hash 清单与生产表对账命中。
 */
@Component
public class ToolkitPromptAssetCollector implements AgentPromptAssetPort {

    private final InvestTools investTools;
    private final PortfolioApplicationService portfolioService;
    private final AllocationApplicationService allocationService;
    private final IntelligenceQueryService intelligenceQueryService;
    private final ObjectMapper mapper;

    public ToolkitPromptAssetCollector(InvestTools investTools,
                                       PortfolioApplicationService portfolioService,
                                       AllocationApplicationService allocationService,
                                       IntelligenceQueryService intelligenceQueryService,
                                       ObjectMapper mapper) {
        this.investTools = investTools;
        this.portfolioService = portfolioService;
        this.allocationService = allocationService;
        this.intelligenceQueryService = intelligenceQueryService;
        this.mapper = mapper;
    }

    @Override
    public List<PromptAsset> collectAssets() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(investTools);
        toolkit.registerTool(new UserInvestTools(0L, portfolioService, allocationService,
                intelligenceQueryService, mapper));
        List<PromptAsset> assets = new ArrayList<>();
        assets.add(new PromptAsset(PromptAssetVersion.TYPE_SYSTEM_PROMPT,
                "system.invest", InvestSystemPrompt.TEXT));
        for (String name : toolkit.getToolNames().stream().sorted().toList()) {
            assets.add(new PromptAsset(PromptAssetVersion.TYPE_TOOL_DESC,
                    "tool." + name, toolkit.getTool(name).getDescription()));
        }
        return List.copyOf(assets);
    }
}

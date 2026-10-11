package com.portfolio.invest.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.report.ReportWorkflowPrompt;
import com.portfolio.invest.application.allocation.AllocationApplicationService;
import com.portfolio.invest.application.eval.AgentPromptAssetPort;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService;
import com.portfolio.invest.application.memory.DigestPrompts;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.application.wiki.WikiApplicationService;
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
    private final WikiApplicationService wikiService;
    private final ObjectMapper mapper;

    public ToolkitPromptAssetCollector(InvestTools investTools,
                                       PortfolioApplicationService portfolioService,
                                       AllocationApplicationService allocationService,
                                       IntelligenceQueryService intelligenceQueryService,
                                       WikiApplicationService wikiService,
                                       ObjectMapper mapper) {
        this.investTools = investTools;
        this.portfolioService = portfolioService;
        this.allocationService = allocationService;
        this.intelligenceQueryService = intelligenceQueryService;
        this.wikiService = wikiService;
        this.mapper = mapper;
    }

    @Override
    public List<PromptAsset> collectAssets() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(investTools);
        toolkit.registerTool(new UserInvestTools(0L, portfolioService, allocationService,
                intelligenceQueryService, wikiService, mapper));
        // 键升序：digest.memory → system.invest → system.invest.profile → system.invest.report → tool.*
        //（MS-31 B5 两新资产：画像注入模板骨架登记占位符形态——实际注入内容系 DB 派生不入
        // 版本链，需求决策 #5 附注；digest.memory 为 B6 每日摘要任务 prompt，键名逐字为
        // eval 侧 hash 清单将来对账锚；MS-32 B6 增 system.invest.report 深研指引块——
        // 研报 Agent 系统提示词的可观测指纹，沿 system.invest.profile 先例）
        List<PromptAsset> assets = new ArrayList<>();
        assets.add(new PromptAsset(PromptAssetVersion.TYPE_SYSTEM_PROMPT,
                "digest.memory", DigestPrompts.SYSTEM));
        assets.add(new PromptAsset(PromptAssetVersion.TYPE_SYSTEM_PROMPT,
                "system.invest", InvestSystemPrompt.TEXT));
        assets.add(new PromptAsset(PromptAssetVersion.TYPE_SYSTEM_PROMPT,
                "system.invest.profile", ProfileContextTemplate.TEXT));
        assets.add(new PromptAsset(PromptAssetVersion.TYPE_SYSTEM_PROMPT,
                "system.invest.report", ReportWorkflowPrompt.BLOCK));
        for (String name : toolkit.getToolNames().stream().sorted().toList()) {
            assets.add(new PromptAsset(PromptAssetVersion.TYPE_TOOL_DESC,
                    "tool." + name, toolkit.getTool(name).getDescription()));
        }
        return List.copyOf(assets);
    }
}

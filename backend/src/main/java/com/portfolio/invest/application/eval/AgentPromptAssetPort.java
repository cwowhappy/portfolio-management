package com.portfolio.invest.application.eval;

import java.util.List;

/**
 * agent 域提示词资产采集端口（agent 包 ToolkitPromptAssetCollector 实现）：系统提示词与
 * 内置 @Tool 描述都定义在 agent 包（InvestSystemPrompt / InvestTools / UserInvestTools），
 * 而分层规范禁止 application 反向依赖 agent——照 IntelligenceChatPort「端口在 application、
 * 实现在外层」的反向同构先例暴露给 {@link PromptVersionRegistrar}。
 *
 * <p>返回原文（未 hash）：hash 计算统一收在 registrar（与 eval 侧 EvalAssetHasher 同一
 * SHA-256 + HexFormat 写法、同一键词表：system.invest / tool.&lt;name&gt;）。
 */
public interface AgentPromptAssetPort {

    /** agent 域提示词资产清单（assetKey 升序稳定，键词表与 eval 侧报告一致）。 */
    List<PromptAsset> collectAssets();

    /**
     * 单项资产原文。
     *
     * @param assetType SYSTEM_PROMPT / TOOL_DESC
     * @param assetKey  词表键（system.invest / tool.&lt;name&gt;）
     * @param content   资产原文（系统提示词全文 / @Tool description）
     */
    record PromptAsset(String assetType, String assetKey, String content) {}
}

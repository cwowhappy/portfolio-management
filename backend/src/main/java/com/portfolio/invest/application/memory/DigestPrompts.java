package com.portfolio.invest.application.memory;

/**
 * 每日记忆摘要提示词（MS-31 F03，设计规格 §4.2 原文）：system 钉死「≤5 条 bullet、保留
 * 关键事实、不添加推断」契约；B6 {@code MemoryDigestService} 消费（user prompt 届时拼装
 * 窗口起止 + timeline 事件 JSON）。SYSTEM 全文即版本资产 {@code digest.memory}
 * （B5 {@code ToolkitPromptAssetCollector} 登记），变更即模板漂移、走版本链可回溯。
 */
public final class DigestPrompts {

    /** 摘要系统提示词（版本资产 digest.memory，§4.2 逐字）。 */
    public static final String SYSTEM =
            "你是投资助理。将用户投资事件流摘要为不超过 5 条要点 bullet"
                    + "（中文，保留标的代码/金额/方向等关键事实，不添加推断）。";

    private DigestPrompts() {
    }
}

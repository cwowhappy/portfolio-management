package com.portfolio.invest.domain.eval;

import java.time.Instant;

/**
 * 提示词资产版本行（prompt_asset_version，MS-30 B3）：append-only 版本链——同
 * (asset_type, asset_key, content_hash) 幂等命中不增行，内容变更插入 version+1 新行
 * （旧行保留，供 eval_run.prompt_versions 快照与版本链看板回溯）。
 *
 * <p>asset_type 枚举与 V5 CHECK 一致（下方 TYPE_* 常量）；eval 侧报告另有 QUESTION_BANK
 * 聚合类型——不入本表（题库 hash 走 eval_run.question_bank_hash 列），收割侧调用 upsert
 * 前自行跳过。asset_key 词表与 eval 侧 EvalAssetHasher 一致（system./tool./skill./intel./
 * rubric. 前缀），保证 eval 报告 hash 清单与生产表 (asset_key, content_hash) 对账命中。
 *
 * @param id           主键（落库前 null）
 * @param assetType    资产类型（五类枚举之一）
 * @param assetKey     资产键（如 'tool.get_quote' / 'skill.tushare_data'）
 * @param version      版本号（同键内容变更 +1，首版 1）
 * @param contentHash  内容 SHA-256 十六进制（64 位小写）
 * @param note         变更说明（自动登记 null，前端渲染「未注记」，PUT /api/admin/prompt-assets/{id}/note 补注）
 * @param registeredAt 登记时间（insert 时不写、库端 DEFAULT now()；读取时回填）
 */
public record PromptAssetVersion(
        Long id,
        String assetType,
        String assetKey,
        int version,
        String contentHash,
        String note,
        Instant registeredAt) {

    /** 系统提示词（InvestSystemPrompt.TEXT，键 system.invest）。 */
    public static final String TYPE_SYSTEM_PROMPT = "SYSTEM_PROMPT";
    /** 内置 @Tool 描述（键 tool.&lt;name&gt;）。 */
    public static final String TYPE_TOOL_DESC = "TOOL_DESC";
    /** Skill 全文（键 skill.&lt;name&gt;）。 */
    public static final String TYPE_SKILL = "SKILL";
    /** 情报 prompt（键 intel.&lt;name&gt;）。 */
    public static final String TYPE_INTEL_PROMPT = "INTEL_PROMPT";
    /** eval rubric（eval-only 资产经报告回流登记，键 rubric.&lt;name&gt;）。 */
    public static final String TYPE_EVAL_RUBRIC = "EVAL_RUBRIC";

    /** 新版本行工厂：自动登记无 note、无 id/registered_at（均由库端生成）。 */
    public static PromptAssetVersion newRegistration(
            String assetType, String assetKey, int version, String contentHash) {
        return new PromptAssetVersion(null, assetType, assetKey, version, contentHash, null, null);
    }
}

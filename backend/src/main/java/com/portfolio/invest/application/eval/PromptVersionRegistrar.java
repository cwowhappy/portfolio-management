package com.portfolio.invest.application.eval;

import com.portfolio.invest.application.intelligence.AnnouncementExtractPrompt;
import com.portfolio.invest.application.intelligence.BriefGenerationService;
import com.portfolio.invest.application.intelligence.NewsExtractPrompt;
import com.portfolio.invest.application.intelligence.PolicyExtractPrompt;
import com.portfolio.invest.domain.eval.PromptAssetVersion;
import com.portfolio.invest.domain.eval.PromptAssetVersionRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * 提示词版本登记（MS-30 B3，设计规格 §4.1/§4.2）：启动时对 main 四类资产（系统提示词 1 +
 * @Tool 描述 16 + SKILL 全文 6 + 情报 prompt 4——LEAD_SYSTEM_PROMPT 改 public 后含第 4 项，
 * 共 27 项）逐项 hash 对账入 prompt_asset_version。幂等：同 (asset_type, asset_key,
 * content_hash) 命中不增行；内容变更插入 version+1 新行（append-only，旧行保留）。
 *
 * <p>键词表与 eval 侧 EvalAssetHasher 一致（system./tool./skill./intel. 前缀），保证 eval
 * 报告 runMeta.assetHashes 与本表 (asset_key, content_hash) 对账命中；hash 写法沿
 * AesGcmSecretCodec 先例（SHA-256 + HexFormat，不引 commons-codec；与 eval 侧各自内联、
 * 允许少量重复——Pre-flight ruling 不建跨源集共享接口）。
 *
 * <p>eval 子进程（--Eval_MODE=true）不注册：版本表在生产库，子进程只产报告 hash 清单、经
 * 收割回流（条件沿 SchedulingConfig 类级 @ConditionalOnExpression 先例）。登记是旁路
 * 可观测性：run() 失败仅 ERROR 日志不阻断启动（沿 AdminSeedRunner 降级先例——观测自身
 * 失效不得拖垮主链路）。
 */
@Component
@ConditionalOnExpression("!'true'.equals('${Eval_MODE:}')")
public class PromptVersionRegistrar implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PromptVersionRegistrar.class);

    private final PromptAssetVersionRepository repository;
    private final AgentPromptAssetPort agentPromptAssets;
    private final ClasspathSkillRepository builtInSkills;

    public PromptVersionRegistrar(PromptAssetVersionRepository repository,
                                  AgentPromptAssetPort agentPromptAssets,
                                  ClasspathSkillRepository builtInSkills) {
        this.repository = repository;
        this.agentPromptAssets = agentPromptAssets;
        this.builtInSkills = builtInSkills;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            registerAll();
        } catch (Exception e) { // 旁路可观测：登记失败不阻断启动
            log.error("提示词资产版本登记失败（不影响应用启动）", e);
        }
    }

    /** 全资产启动对账：逐项 upsert，收尾留痕新登记/未变更计数。 */
    public void registerAll() {
        int registered = 0;
        int unchanged = 0;
        for (AssetCandidate asset : collectAssets()) {
            if (upsert(asset.assetType(), asset.assetKey(), sha256Hex(asset.content())).registered()) {
                registered++;
            } else {
                unchanged++;
            }
        }
        log.info("提示词资产版本对账完成：共 {} 项（新登记 {}，未变更 {}）",
                registered + unchanged, registered, unchanged);
    }

    /**
     * 幂等登记单项资产（收割端 EvalHarvester 复用：eval 报告回流的 rubric 逐文件 hash
     * 以 EVAL_RUBRIC 类型逐条 upsert）。
     *
     * <p>枚举边界：asset_type 须为 V5 CHECK 五类（SYSTEM_PROMPT/TOOL_DESC/SKILL/
     * INTEL_PROMPT/EVAL_RUBRIC）；eval 报告另含 QUESTION_BANK 聚合类型——不入版本表
     * （题库 hash 走 eval_run.question_bank_hash），收割侧调用前自行跳过，本方法不做
     * 类型过滤（端口保持可复用）。
     *
     * @return 对账结果（registered=true 为本次新插入的版本行）
     */
    public UpsertResult upsert(String assetType, String assetKey, String contentHash) {
        Optional<Integer> existing = repository.findVersionByHash(assetType, assetKey, contentHash);
        if (existing.isPresent()) {
            return new UpsertResult(assetType, assetKey, existing.get(), false);
        }
        int next = latestVersion(assetType, assetKey) + 1;
        repository.insert(PromptAssetVersion.newRegistration(assetType, assetKey, next, contentHash));
        log.info("提示词资产新版本登记：{} {} v{}（content_hash={}）",
                assetType, assetKey, next, shortHash(contentHash));
        return new UpsertResult(assetType, assetKey, next, true);
    }

    private List<AssetCandidate> collectAssets() {
        List<AssetCandidate> assets = new ArrayList<>();
        for (AgentPromptAssetPort.PromptAsset asset : agentPromptAssets.collectAssets()) {
            assets.add(new AssetCandidate(asset.assetType(), asset.assetKey(), asset.content()));
        }
        builtInSkills.getAllSkills().forEach(skill -> assets.add(new AssetCandidate(
                PromptAssetVersion.TYPE_SKILL, "skill." + skill.getName(), skill.getSkillContent())));
        assets.add(new AssetCandidate(PromptAssetVersion.TYPE_INTEL_PROMPT,
                "intel.news_extract", NewsExtractPrompt.SYSTEM_PROMPT));
        assets.add(new AssetCandidate(PromptAssetVersion.TYPE_INTEL_PROMPT,
                "intel.announcement_extract", AnnouncementExtractPrompt.SYSTEM_PROMPT));
        assets.add(new AssetCandidate(PromptAssetVersion.TYPE_INTEL_PROMPT,
                "intel.policy_extract", PolicyExtractPrompt.SYSTEM_PROMPT));
        assets.add(new AssetCandidate(PromptAssetVersion.TYPE_INTEL_PROMPT,
                "intel.brief_lead", BriefGenerationService.LEAD_SYSTEM_PROMPT));
        return assets;
    }

    /** 该键当前最高版本号（无历史为 0，新版本 = 其 + 1）。表规模小，快照过滤即可。 */
    private int latestVersion(String assetType, String assetKey) {
        return repository.latestSnapshot().stream()
                .filter(v -> assetType.equals(v.assetType()) && assetKey.equals(v.assetKey()))
                .mapToInt(PromptAssetVersion::version)
                .max()
                .orElse(0);
    }

    /** 日志短码：hash 前 8 位（全量 64 位已入库，日志够定位即可；不足 8 位取全程，日志助手不抛）。 */
    private static String shortHash(String contentHash) {
        return contentHash.substring(0, Math.min(8, contentHash.length()));
    }

    /** SHA-256 十六进制（沿 AesGcmSecretCodec.java:125-134 先例，不引 commons-codec）。 */
    private static String sha256Hex(String content) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 采集面中间结构（类型/键/原文）。 */
    private record AssetCandidate(String assetType, String assetKey, String content) {}

    /**
     * 单项对账结果。
     *
     * @param version    命中或新插入的版本号
     * @param registered true=本次新插入版本行；false=hash 未变（原地命中）
     */
    public record UpsertResult(String assetType, String assetKey, int version, boolean registered) {}
}

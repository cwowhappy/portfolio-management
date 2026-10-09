package com.portfolio.invest.eval;

import com.portfolio.invest.agent.InvestSystemPrompt;
import com.portfolio.invest.agent.InvestTools;
import com.portfolio.invest.agent.UserInvestTools;
import com.portfolio.invest.application.intelligence.AnnouncementExtractPrompt;
import com.portfolio.invest.application.intelligence.BriefGenerationService;
import com.portfolio.invest.application.intelligence.NewsExtractPrompt;
import com.portfolio.invest.application.intelligence.PolicyExtractPrompt;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 五类资产指纹采集（MS-30 B2，设计规格 §4.1 eval 侧报告回流通道）：eval 源集内联实现——
 * Task 4 才建 main 侧采集器（PromptVersionRegistrar），两处允许少量 hash 逻辑重复，
 * 不建跨源集共享接口。产出 {@code runMeta.assetHashes}（main 四类 + rubric + 题库聚合，
 * 「类型:键:内容hash」清单——版本表在生产库，子进程不可见，hash 清单经报告回流）与
 * {@code runMeta.evalAssets}（rubric/题库逐文件，收割端 upsert EVAL_RUBRIC 资产）。
 *
 * <p>采集全程零 Spring（{@code --list} 干跑无上下文，干跑与真跑必须同一实现）：
 * 系统提示词/情报 prompt 经 main 类常量直读（情报 prompt 4 项——含 Task 4 改 public 后
 * 补录的 {@code BriefGenerationService.LEAD_SYSTEM_PROMPT}，与 main 侧 registrar 27 项
 * 口径对齐）；@Tool 描述新建裸 Toolkit 枚举（UserToolkitFactory.build 同源两件：InvestTools + UserInvestTools，
 * 天然在 decorateWithRecording 之前——装饰后 getTool 返回 decorator）；@Tool 描述是注解常量、
 * 与服务实例状态无关，依赖传 null 仅注册期反射、无方法调用；Skill 直读独立
 * ClasspathSkillRepository（Spring 单例管 JAR 虚拟 FS 生命周期，eval 进程短命且用后即关）；
 * rubric/题库经 classpath 逐文件读取（题库聚合 = 各文件 hash 排序后再 hash，内容稳定指纹）。
 * SHA-256+HexFormat 写法沿 {@code AesGcmSecretCodec.java:125-134} 唯一 Java 侧先例。
 */
public final class EvalAssetHasher {

    /** 报告内资产类型口径：前五类与 prompt_asset_version.asset_type 枚举（V5）一致；QUESTION_BANK 为题库聚合项类型（收割端落 eval_run.question_bank_hash，不入版本表）。 */
    static final String TYPE_SYSTEM_PROMPT = "SYSTEM_PROMPT";
    static final String TYPE_TOOL_DESC = "TOOL_DESC";
    static final String TYPE_SKILL = "SKILL";
    static final String TYPE_INTEL_PROMPT = "INTEL_PROMPT";
    static final String TYPE_EVAL_RUBRIC = "EVAL_RUBRIC";
    static final String TYPE_QUESTION_BANK = "QUESTION_BANK";

    /** 采集结果：assetHashes=完整指纹清单（五类 + 题库聚合）、evalAssets=eval-only 逐文件、questionBankHash=题库聚合 hash。 */
    public record Collected(List<ReportWriter.AssetHash> assetHashes,
                            List<ReportWriter.AssetHash> evalAssets,
                            String questionBankHash) {

        /** {@code --list} 干跑/装载日志的类型摘要（如 SYSTEM_PROMPT=1 TOOL_DESC=16 …），供产物断言。 */
        public String typeSummary() {
            return assetHashes.stream()
                    .collect(Collectors.groupingBy(ReportWriter.AssetHash::assetType,
                            TreeMap::new, Collectors.counting()))
                    .entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(" "));
        }
    }

    private EvalAssetHasher() {}

    public static Collected collectStandalone() {
        List<ReportWriter.AssetHash> mainAssets = new ArrayList<>();
        mainAssets.add(new ReportWriter.AssetHash(TYPE_SYSTEM_PROMPT,
                "system.invest", sha256Hex(InvestSystemPrompt.TEXT)));
        mainAssets.addAll(toolDescriptions());
        mainAssets.addAll(skillContents());
        mainAssets.addAll(intelligencePrompts());

        List<ReportWriter.AssetHash> rubric = rubricFiles();
        List<ReportWriter.AssetHash> questionFiles = questionBankFiles();
        // 题库聚合 = 各文件 hash 排序后再 hash（与文件名无关的内容稳定指纹）
        String aggregate = sha256Hex(String.join("\n", questionFiles.stream()
                .map(ReportWriter.AssetHash::contentHash).sorted().toList()));

        List<ReportWriter.AssetHash> assetHashes = new ArrayList<>(mainAssets);
        assetHashes.addAll(rubric);
        assetHashes.add(new ReportWriter.AssetHash(TYPE_QUESTION_BANK, "question_bank", aggregate));
        List<ReportWriter.AssetHash> evalAssets = new ArrayList<>(rubric);
        evalAssets.addAll(questionFiles);
        return new Collected(List.copyOf(assetHashes), List.copyOf(evalAssets), aggregate);
    }

    // ———— main 四类（§4.1 采集表） ————

    /** @Tool 描述 ×N：新建裸 Toolkit 枚举（decorateWithRecording 之前），键沿 prompt_asset_version.asset_key 示例（tool.get_quote）。 */
    private static List<ReportWriter.AssetHash> toolDescriptions() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new InvestTools(null, null, null, null, null, null, null, null));
        toolkit.registerTool(new UserInvestTools(0L, null, null, null, null));
        return toolkit.getToolNames().stream().sorted()
                .map(name -> new ReportWriter.AssetHash(TYPE_TOOL_DESC, "tool." + name,
                        sha256Hex(toolkit.getTool(name).getDescription())))
                .toList();
    }

    /** SKILL.md ×N：ClasspathSkillRepository 全文（getAllSkills → getSkillContent，与 SkillApplicationService 同款遍历）。 */
    private static List<ReportWriter.AssetHash> skillContents() {
        try (ClasspathSkillRepository repository = new ClasspathSkillRepository("skills")) {
            return repository.getAllSkills().stream()
                    .sorted(Comparator.comparing(skill -> skill.getName()))
                    .map(skill -> new ReportWriter.AssetHash(TYPE_SKILL, "skill." + skill.getName(),
                            sha256Hex(skill.getSkillContent())))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Skill 资产采集失败（classpath:skills）", e);
        }
    }

    /** 情报 prompt ×4：public 常量直读（含 LEAD_SYSTEM_PROMPT——Task 4 改 public 后补录，I1 口径与 main 侧对齐）。 */
    private static List<ReportWriter.AssetHash> intelligencePrompts() {
        return List.of(
                new ReportWriter.AssetHash(TYPE_INTEL_PROMPT, "intel.news_extract",
                        sha256Hex(NewsExtractPrompt.SYSTEM_PROMPT)),
                new ReportWriter.AssetHash(TYPE_INTEL_PROMPT, "intel.announcement_extract",
                        sha256Hex(AnnouncementExtractPrompt.SYSTEM_PROMPT)),
                new ReportWriter.AssetHash(TYPE_INTEL_PROMPT, "intel.policy_extract",
                        sha256Hex(PolicyExtractPrompt.SYSTEM_PROMPT)),
                new ReportWriter.AssetHash(TYPE_INTEL_PROMPT, "intel.brief_lead",
                        sha256Hex(BriefGenerationService.LEAD_SYSTEM_PROMPT)));
    }

    // ———— eval-only 资产（rubric/题库，classpath 逐文件） ————

    /** rubric ×N：文件名剥 .md、剥 rubric- 前缀为键（rubric-answer-quality.md → rubric.answer-quality）。 */
    private static List<ReportWriter.AssetHash> rubricFiles() {
        return classpathFiles("classpath:rubric/*.md").entrySet().stream()
                .map(e -> new ReportWriter.AssetHash(TYPE_EVAL_RUBRIC,
                        "rubric." + rubricKey(e.getKey()), sha256Hex(e.getValue())))
                .toList();
    }

    private static String rubricKey(String filename) {
        String name = filename.endsWith(".md") ? filename.substring(0, filename.length() - 3) : filename;
        return name.startsWith("rubric-") ? name.substring("rubric-".length()) : name;
    }

    /** 题库逐文件（questions/*.yaml；extraction 题库属抽取评测独立报告，不计入）。 */
    private static List<ReportWriter.AssetHash> questionBankFiles() {
        return classpathFiles("classpath:questions/*.yaml").entrySet().stream()
                .map(e -> new ReportWriter.AssetHash(TYPE_QUESTION_BANK,
                        "question_bank." + e.getKey().replaceAll("\\.yaml$", ""),
                        sha256Hex(e.getValue())))
                .toList();
    }

    /** classpath 目录逐文件读取（TreeMap 按文件名排序，遍历序稳定）。 */
    private static Map<String, String> classpathFiles(String pattern) {
        try {
            Map<String, String> contents = new TreeMap<>();
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(pattern)) {
                String filename = resource.getFilename();
                if (filename == null) continue;
                try (InputStream in = resource.getInputStream()) {
                    contents.put(filename, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            if (contents.isEmpty()) {
                throw new IllegalStateException("评估资源目录为空: " + pattern);
            }
            return contents;
        } catch (IOException e) {
            throw new IllegalStateException("评估资源读取失败: " + pattern, e);
        }
    }

    /** SHA-256 十六进制（沿 AesGcmSecretCodec.java:125-134 先例，不引 commons-codec）。 */
    static String sha256Hex(String content) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

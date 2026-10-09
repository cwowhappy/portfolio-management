package com.portfolio.invest.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 五类资产指纹采集（MS-30 B2，设计规格 §4.1 eval 侧回流通道）：eval 源集内联实现（Task 4
 * 已建 main 侧采集器，两处允许少量 hash 逻辑重复，不建跨源集共享接口）。采集全程零 Spring——
 * @Tool 描述取新建 Toolkit 枚举（装饰前天然成立），Skill/情报 prompt/系统提示词经类常量与
 * classpath 直读，与 {@code --list} 干跑同源（干跑无上下文，两路必须同一实现）。
 * 类型计数即 V5 表 prompt_asset_version.asset_type 枚举口径；情报 prompt 4 项含
 * LEAD_SYSTEM_PROMPT（Task 4 改 public 后已补录，I1 口径与 main 侧 27 项对齐）。
 */
class EvalAssetHasherTest {

    @DisplayName("SHA-256 十六进制：已知向量校验（写法沿 AesGcmSecretCodec.java:125-134 先例）")
    @Test
    void givenKnownInput_whenHashed_thenHexMatchesKnownVector() {
        assertThat(EvalAssetHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @DisplayName("采集清单：27 main（1+16+6+4）+ 5 rubric + 1 题库聚合 = 33 项，类型计数符合设计规格 §4.1")
    @Test
    void givenStandaloneCollection_whenGroupedByType_thenCountsMatchDesignSpec() {
        EvalAssetHasher.Collected collected = EvalAssetHasher.collectStandalone();

        Map<String, Long> byType = collected.assetHashes().stream()
                .collect(Collectors.groupingBy(ReportWriter.AssetHash::assetType, Collectors.counting()));
        assertThat(byType).containsEntry("SYSTEM_PROMPT", 1L)
                .containsEntry("TOOL_DESC", 16L)
                .containsEntry("SKILL", 6L)
                .containsEntry("INTEL_PROMPT", 4L)
                .containsEntry("EVAL_RUBRIC", 5L)
                .containsEntry("QUESTION_BANK", 1L);
        assertThat(collected.assetHashes()).hasSize(33);
        assertThat(collected.assetHashes())
                .allMatch(h -> h.contentHash().matches("[0-9a-f]{64}"));
        // (assetType, assetKey) 全清单唯一（收割端 upsert 主键语义）
        long distinctKeys = collected.assetHashes().stream()
                .map(h -> h.assetType() + ":" + h.assetKey()).distinct().count();
        assertThat(distinctKeys).isEqualTo(33);
        // 题库聚合项 hash 与顶层 questionBankHash 一致
        String aggregate = collected.assetHashes().stream()
                .filter(h -> "QUESTION_BANK".equals(h.assetType()) && "question_bank".equals(h.assetKey()))
                .findFirst().orElseThrow().contentHash();
        assertThat(aggregate).isEqualTo(collected.questionBankHash());
    }

    @DisplayName("资产键形：system.invest / tool.* / skill.* / intel.* / rubric.*（沿 prompt_asset_version.asset_key 示例）")
    @Test
    void givenStandaloneCollection_whenKeyed_thenKeyConventionsHold() {
        List<String> keys = EvalAssetHasher.collectStandalone().assetHashes().stream()
                .map(ReportWriter.AssetHash::assetKey).toList();
        assertThat(keys).contains("system.invest", "tool.get_quote", "skill.tushare_data",
                "question_bank");
        // rubric 键 = 文件名剥 .md、剥 rubric- 前缀（rubric-answer-quality.md → rubric.answer-quality）
        assertThat(keys).contains("rubric.answer-quality", "rubric.extraction",
                "rubric.judge-prompt-template", "rubric.hitl-interrupt", "rubric.refusal-naturalness");
        assertThat(keys.stream().filter(k -> k.startsWith("tool.")).count()).isEqualTo(16);
        assertThat(keys.stream().filter(k -> k.startsWith("skill.")).count()).isEqualTo(6);
    }

    @DisplayName("情报提示词：4 处 public 常量（含 LEAD_SYSTEM_PROMPT——I1 口径补齐，与 main 侧 27 项对齐）")
    @Test
    void givenIntelligencePrompts_whenCollected_thenFourPublicConstantsIncludingLead() {
        List<String> intelKeys = EvalAssetHasher.collectStandalone().assetHashes().stream()
                .filter(h -> "INTEL_PROMPT".equals(h.assetType()))
                .map(ReportWriter.AssetHash::assetKey).sorted().toList();
        assertThat(intelKeys).containsExactly(
                "intel.announcement_extract", "intel.brief_lead",
                "intel.news_extract", "intel.policy_extract");
    }

    @DisplayName("evalAssets：rubric 5 文件 + 题库 9 文件逐文件指纹（eval-only 资产回流登记通道；MS-30 E1 增 market-fact/metric-calc）")
    @Test
    void givenStandaloneCollection_whenEvalAssetsListed_thenRubricAndQuestionFiles() {
        EvalAssetHasher.Collected collected = EvalAssetHasher.collectStandalone();

        Map<String, Long> byType = collected.evalAssets().stream()
                .collect(Collectors.groupingBy(ReportWriter.AssetHash::assetType, Collectors.counting()));
        assertThat(byType).containsEntry("EVAL_RUBRIC", 5L).containsEntry("QUESTION_BANK", 9L);
        assertThat(collected.evalAssets()).hasSize(14);
        List<String> keys = collected.evalAssets().stream()
                .map(ReportWriter.AssetHash::assetKey).toList();
        assertThat(keys).contains("question_bank.single-turn", "question_bank.market-fact",
                "question_bank.metric-calc", "question_bank.real");
    }

    @DisplayName("题库聚合 hash 确定性：两次采集一致且不等于任何单文件指纹")
    @Test
    void givenTwoCollections_whenCompared_thenQuestionBankHashDeterministic() {
        EvalAssetHasher.Collected first = EvalAssetHasher.collectStandalone();
        EvalAssetHasher.Collected second = EvalAssetHasher.collectStandalone();

        assertThat(second.questionBankHash()).isEqualTo(first.questionBankHash());
        List<String> perFile = first.evalAssets().stream()
                .filter(h -> "QUESTION_BANK".equals(h.assetType()))
                .map(ReportWriter.AssetHash::contentHash).toList();
        assertThat(first.questionBankHash()).isNotIn(perFile);
    }
}

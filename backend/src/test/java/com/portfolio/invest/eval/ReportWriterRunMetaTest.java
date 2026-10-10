package com.portfolio.invest.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 报告 schema v2（MS-30 B2，设计规格 §2.4）：顶层 runMeta（runId/起止时间/触发方式/五类资产
 * hash 清单/eval-only 资产/题库聚合 hash/总时长/完整度）+ {@code --compare} 基准可比性指纹
 * （v1 旧档无 runMeta、题库 hash 或 rubric 指纹漂移 → BASELINE_INCOMPARABLE）。
 * ReportWriter 在 eval 源集（test 类路径已挂 eval 输出），既有行为（summary/questions/
 * questionChanges）不回退由可比用例顺带守住。
 */
class ReportWriterRunMetaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private final ReportWriter.Meta meta =
            new ReportWriter.Meta("stub", "deepseek-test", "https://base.example", "judge-test", null, 120_000);

    // ———— runMeta 装配 ————

    @DisplayName("v2 写出：schema=eval-agent-report/2 且 runMeta 字段齐全（hash 清单非版本号）")
    @Test
    void givenRunMeta_whenWritten_thenSchema2AndRunMetaComplete() throws IOException {
        JsonNode report = writeAndRead(runMeta("1".repeat(64)), null);

        assertThat(report.path("schema").asText()).isEqualTo("eval-agent-report/2");
        JsonNode runMeta = report.path("runMeta");
        assertThat(runMeta.path("runId").asText()).isEqualTo("run-uuid-1");
        assertThat(runMeta.path("startedAt").asText()).isEqualTo("2026-10-09T10:00:00+08:00");
        assertThat(runMeta.path("finishedAt").asText()).isEqualTo("2026-10-09T10:05:00+08:00");
        assertThat(runMeta.path("triggeredBy").asText()).isEqualTo("MANUAL");
        assertThat(runMeta.path("questionBankHash").asText()).isEqualTo("1".repeat(64));
        assertThat(runMeta.path("totalDurationMs").asLong()).isEqualTo(300_000L);
        assertThat(runMeta.path("completeness").asText()).isEqualTo("FULL");
        assertThat(runMeta.path("assetHashes").size()).isEqualTo(3);
        assertThat(runMeta.path("evalAssets").size()).isEqualTo(2);
    }

    @DisplayName("assetHashes 元素形：{assetType, assetKey, contentHash} 三字段（收割端 upsert 入参）")
    @Test
    void givenAssetHashEntries_whenSerialized_thenExactlyThreeFields() throws IOException {
        JsonNode report = writeAndRead(runMeta("1".repeat(64)), null);

        JsonNode first = report.path("runMeta").path("assetHashes").get(0);
        Set<String> fields = new HashSet<>();
        first.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("assetType", "assetKey", "contentHash");
        assertThat(first.path("assetType").asText()).isEqualTo("SYSTEM_PROMPT");
        assertThat(first.path("assetKey").asText()).isEqualTo("system.invest");
        assertThat(first.path("contentHash").asText()).isEqualTo("a".repeat(64));
    }

    @DisplayName("markdown 报告头含运行元信息与题库 hash 摘要（人读通道不缺位）")
    @Test
    void givenRunMeta_whenWritten_thenMarkdownMentionsRunSummary() throws IOException {
        ReportWriter.Written written = new ReportWriter(tmp).write(List.of(), meta, runMeta("1".repeat(64)), null);
        String md = Files.readString(written.md(), StandardCharsets.UTF_8);
        assertThat(md).contains("run-uuid-1").contains("MANUAL").contains("FULL").contains("11111111");
    }

    @DisplayName("token 预算超限：runMeta 携 tokenBudgetExceeded=true（markdown 运行行同步 PARTIAL）")
    @Test
    void givenTokenBudgetExceeded_whenWritten_thenFlagPresentAndMarkdownPartial() throws IOException {
        ReportWriter.RunMeta runMeta = runMetaWithRubricHash("1".repeat(64), "b".repeat(64))
                .withTokenBudgetExceeded();
        JsonNode report = writeAndRead(runMeta, null);

        assertThat(report.path("runMeta").path("tokenBudgetExceeded").asBoolean(false)).isTrue();
        assertThat(report.path("runMeta").path("completeness").asText()).isEqualTo("PARTIAL");
        String md = Files.readString(new ReportWriter(tmp).write(List.of(), meta, runMeta, null).md(),
                StandardCharsets.UTF_8);
        assertThat(md).contains("PARTIAL");
    }

    @DisplayName("未超限报告：runMeta 无 tokenBudgetExceeded 键（可选字段向后兼容，不改既有字段名）")
    @Test
    void givenNoTokenBudgetExceeded_whenWritten_thenFieldAbsent() throws IOException {
        JsonNode report = writeAndRead(runMeta("1".repeat(64)), null);

        assertThat(report.path("runMeta").has("tokenBudgetExceeded")).isFalse();
        assertThat(report.path("runMeta").path("completeness").asText()).isEqualTo("FULL");
    }

    // ———— --compare 基准可比性 ————

    @DisplayName("上次报告为 v1 旧档（无 runMeta）：标 BASELINE_INCOMPARABLE，既有 questionChanges 不回退")
    @Test
    void givenV1PreviousReport_whenCompared_thenMarkedBaselineIncomparable() throws IOException {
        Path v1 = writeV1Fixture();
        JsonNode report = writeAndRead(runMeta("1".repeat(64)), v1);

        JsonNode compare = report.path("compare");
        assertThat(compare.path("incomparable").asBoolean()).isTrue();
        assertThat(compare.path("incomparableReasons").size()).isEqualTo(1);
        assertThat(compare.path("incomparableReasons").get(0).asText())
                .contains("BASELINE_INCOMPARABLE").contains("runMeta");
        assertThat(compare.path("questionChanges").size()).isZero();
    }

    @DisplayName("上次报告同指纹 v2：可比（incomparable=false 且理由空），questionChanges 照常列出")
    @Test
    void givenSameFingerprintV2Previous_whenCompared_thenComparable() throws IOException {
        ReportWriter.Written prev = new ReportWriter(tmp).write(List.of(), meta, runMeta("1".repeat(64)), null);
        JsonNode report = writeAndRead(runMeta("1".repeat(64)), prev.json());

        JsonNode compare = report.path("compare");
        assertThat(compare.path("incomparable").asBoolean()).isFalse();
        assertThat(compare.path("incomparableReasons").size()).isZero();
        assertThat(compare.has("questionChanges")).isTrue();
    }

    @DisplayName("题库 hash 漂移：标不可比（§3.1 可比性指纹之一）")
    @Test
    void givenQuestionBankHashDrift_whenCompared_thenIncomparable() throws IOException {
        ReportWriter.Written prev = new ReportWriter(tmp).write(List.of(), meta, runMeta("1".repeat(64)), null);
        JsonNode report = writeAndRead(runMeta("2".repeat(64)), prev.json());

        JsonNode compare = report.path("compare");
        assertThat(compare.path("incomparable").asBoolean()).isTrue();
        assertThat(compare.path("incomparableReasons").get(0).asText())
                .contains("BASELINE_INCOMPARABLE").contains("题库 hash");
    }

    @DisplayName("rubric 指纹漂移：标不可比并点名资产键（judge 判据变更即不可比）")
    @Test
    void givenRubricHashDrift_whenCompared_thenIncomparableNamingAssetKey() throws IOException {
        ReportWriter.Written prev = new ReportWriter(tmp).write(List.of(), meta, runMeta("1".repeat(64)), null);
        JsonNode report = writeAndRead(runMetaWithRubricHash("1".repeat(64), "f".repeat(64)), prev.json());

        JsonNode compare = report.path("compare");
        assertThat(compare.path("incomparable").asBoolean()).isTrue();
        assertThat(compare.path("incomparableReasons").get(0).asText())
                .contains("BASELINE_INCOMPARABLE").contains("rubric.answer-quality");
    }

    // ———— 夹具 ————

    private ReportWriter.RunMeta runMeta(String questionBankHash) {
        return runMetaWithRubricHash(questionBankHash, "b".repeat(64));
    }

    private ReportWriter.RunMeta runMetaWithRubricHash(String questionBankHash, String rubricHash) {
        return new ReportWriter.RunMeta(
                "run-uuid-1", "2026-10-09T10:00:00+08:00", "2026-10-09T10:05:00+08:00", "MANUAL",
                List.of(
                        new ReportWriter.AssetHash("SYSTEM_PROMPT", "system.invest", "a".repeat(64)),
                        new ReportWriter.AssetHash("EVAL_RUBRIC", "rubric.answer-quality", rubricHash),
                        new ReportWriter.AssetHash("QUESTION_BANK", "question_bank", questionBankHash)),
                List.of(
                        new ReportWriter.AssetHash("EVAL_RUBRIC", "rubric.answer-quality", rubricHash),
                        new ReportWriter.AssetHash("QUESTION_BANK", "question_bank.single-turn", "c".repeat(64))),
                questionBankHash, 300_000, "FULL", null);
    }

    private JsonNode writeAndRead(ReportWriter.RunMeta runMeta, Path compareWith) throws IOException {
        ReportWriter.Written written = new ReportWriter(tmp).write(List.of(), meta, runMeta, compareWith);
        return MAPPER.readTree(written.json().toFile());
    }

    /** v1 旧档形态（schema /1，无 runMeta）——升级前的历史报告。 */
    private Path writeV1Fixture() throws IOException {
        Path fixture = tmp.resolve("v1-report.json");
        Files.writeString(fixture, """
                {"schema": "eval-agent-report/1", "generatedAt": "2026-09-01T00:00:00+08:00",
                 "mode": "stub", "summary": {"total": 0}, "questions": []}
                """, StandardCharsets.UTF_8);
        return fixture;
    }
}

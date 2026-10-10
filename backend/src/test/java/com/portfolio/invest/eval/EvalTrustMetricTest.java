package com.portfolio.invest.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.trust.TrustAgentHook;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MS-29 附加度量（需求 D4-1）：诱导题（HALLUCINATION_INDUCTION）上校验器的未溯源标注率作
 * eval 报告附加度量列。可得面：TrustAgentHook 回合收尾发 CUSTOM trust.anchors（value.payload.stats
 * 含 verified/sourced/unverified，TrustHookIntegrationTest 钉过 SSE 帧可达）——本类钉：
 * ①AguiEventExtractor 从 SSE 事件聚合 TrustStats（跨轮累计）；②ReportWriter 对诱导类题目
 * 附带 trust 统计（JSON trustStats 节 + Markdown 诱导类附加度量表，事件缺席时明示边界）；
 * ③事件名常量与 main 侧 TrustAgentHook.ANCHORS_EVENT 逐字对齐（错字则统计静默缺席）。
 */
class EvalTrustMetricTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private static AguiDriver.SseTurn turn(int index, String... eventJsons) {
        List<JsonNode> events = java.util.Arrays.stream(eventJsons)
                .map(json -> {
                    try {
                        return (JsonNode) MAPPER.readTree(json);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
        return new AguiDriver.SseTurn(index, "{}", String.join("\n", eventJsons), events, 0, null);
    }

    private static String anchorsEvent(int verified, int sourced, int unverified) {
        return """
                {"type":"CUSTOM","name":"trust.anchors","value":{"messageId":"m-1","payload":{"v":1,
                 "stats":{"verified":%d,"sourced":%d,"unverified":%d}}}}""".formatted(verified, sourced, unverified);
    }

    // ———— 抽取：CUSTOM trust.anchors 跨轮累计 ————

    @DisplayName("trust.anchors 事件聚合：跨轮 verified/sourced/unverified 累计 + 事件数")
    @Test
    void givenAnchorsEvents_whenExtracted_thenStatsAggregated() {
        AguiEventExtractor.Transcript t = AguiEventExtractor.extract(List.of(
                turn(0, anchorsEvent(2, 1, 0)),
                turn(1, anchorsEvent(1, 0, 2))));
        assertThat(t.trustStats().verified()).isEqualTo(3);
        assertThat(t.trustStats().sourced()).isEqualTo(1);
        assertThat(t.trustStats().unverified()).isEqualTo(2);
        assertThat(t.trustStats().events()).isEqualTo(2);
        assertThat(t.trustStats().unverifiedRatio()).isEqualTo(2.0 / 6.0);
    }

    @DisplayName("trust.anchors 缺席：events=0（既有 token_usage 等事件不受影响）")
    @Test
    void givenNoAnchorsEvents_whenExtracted_thenZeroEvents() {
        AguiEventExtractor.Transcript t = AguiEventExtractor.extract(List.of(
                turn(0, "{\"type\":\"CUSTOM\",\"name\":\"token_usage\",\"value\":{\"cumulative\":{\"totalTokens\":10}}}")));
        assertThat(t.trustStats().events()).isZero();
        assertThat(t.trustStats().unverifiedRatio()).isZero();
    }

    @DisplayName("事件名常量与 main 侧逐字对齐：TRUST_ANCHORS_EVENT == TrustAgentHook.ANCHORS_EVENT")
    @Test
    void givenWireNameConstant_whenCheckedAgainstMainHook_thenAlignedVerbatim() {
        assertThat(AguiEventExtractor.TRUST_ANCHORS_EVENT).isEqualTo(TrustAgentHook.ANCHORS_EVENT);
    }

    // ———— 报告：诱导类题目附带 trust 附加度量 ————

    @DisplayName("诱导类题目带 trustStats：JSON trustStats 节 + Markdown 诱导类附加度量表（未溯源标注率）")
    @Test
    void givenInductionOutcomeWithStats_whenWritten_thenTrustMetricRendered() throws IOException {
        AguiEventExtractor.TrustStats stats = new AguiEventExtractor.TrustStats(2, 1, 1, 1);
        QuestionOutcome outcome = new QuestionOutcome(
                question("hi-predict-tomorrow", "HALLUCINATION_INDUCTION"), QuestionOutcome.Status.PASS, null, null,
                "eval_user", "thread-1", 1000, List.of(), List.of(), null, null, stats,
                "无法预测。");
        ReportWriter.Written written = write(List.of(outcome));

        JsonNode node = MAPPER.readTree(written.json().toFile());
        JsonNode trust = node.path("questions").get(0).path("trustStats");
        assertThat(trust.path("verified").asInt()).isEqualTo(2);
        assertThat(trust.path("sourced").asInt()).isEqualTo(1);
        assertThat(trust.path("unverified").asInt()).isEqualTo(1);
        assertThat(trust.path("events").asInt()).isEqualTo(1);

        String md = Files.readString(written.md(), StandardCharsets.UTF_8);
        assertThat(md).contains("诱导类 trust 附加度量").contains("hi-predict-tomorrow");
        assertThat(md).contains("25.0%"); // 未溯源标注率 1/(2+1+1)
    }

    @DisplayName("诱导类题目 trust 事件缺席：附加度量表明示缺席（边界可见，不静默吞掉）")
    @Test
    void givenInductionOutcomeWithoutStats_whenWritten_thenAbsenceNoted() throws IOException {
        QuestionOutcome outcome = new QuestionOutcome(
                question("hi-fake-policy", "HALLUCINATION_INDUCTION"), QuestionOutcome.Status.PASS, null, null,
                "eval_user", "thread-2", 1000, List.of(), List.of(), null, null, null, "查无。");
        ReportWriter.Written written = write(List.of(outcome));

        JsonNode node = MAPPER.readTree(written.json().toFile());
        assertThat(node.path("questions").get(0).has("trustStats")).isFalse();

        String md = Files.readString(written.md(), StandardCharsets.UTF_8);
        assertThat(md).contains("诱导类 trust 附加度量").contains("hi-fake-policy").contains("缺席");
    }

    @DisplayName("非诱导类题目带 trustStats：JSON 照常携带，Markdown 诱导类节不出现")
    @Test
    void givenNonInductionOutcomeWithStats_whenWritten_thenNoInductionSection() throws IOException {
        QuestionOutcome outcome = new QuestionOutcome(
                question("mf-quote-maotai-by-code", "MARKET_FACT"), QuestionOutcome.Status.PASS, null, null,
                "eval_user", "thread-3", 1000, List.of(), List.of(), null, null,
                new AguiEventExtractor.TrustStats(3, 0, 0, 1), "1735.86 元。");
        ReportWriter.Written written = write(List.of(outcome));

        assertThat(MAPPER.readTree(written.json().toFile())
                .path("questions").get(0).path("trustStats").path("events").asInt()).isEqualTo(1);
        assertThat(Files.readString(written.md(), StandardCharsets.UTF_8))
                .doesNotContain("诱导类 trust 附加度量");
    }

    // ———— 夹具 ————

    private static EvalQuestion question(String id, String category) {
        return new EvalQuestion(id, category, "stub", List.of("测试问题"),
                new EvalQuestion.Expect(null, null, null, null, null, null, null, null, null,
                        new EvalQuestion.HallucinationGuard(null), null),
                "rubric-refusal-naturalness", null);
    }

    private ReportWriter.Written write(List<QuestionOutcome> outcomes) throws IOException {
        ReportWriter.RunMeta runMeta = new ReportWriter.RunMeta(
                "run-uuid", "2026-10-10T10:00:00+08:00", "2026-10-10T10:05:00+08:00", "MANUAL",
                List.of(), List.of(), "a".repeat(64), 300_000, "FULL", null);
        ReportWriter.Meta meta =
                new ReportWriter.Meta("stub", "deepseek-test", "https://base.example", "judge-test", null, 120_000);
        return new ReportWriter(tmp).write(outcomes, meta, runMeta, null);
    }
}

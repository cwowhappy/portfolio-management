package com.portfolio.invest.application.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.portfolio.invest.application.alert.AlertNotifier;
import com.portfolio.invest.application.auth.MailSender;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.eval.EvalRunHarvest;
import com.portfolio.invest.domain.eval.EvalRunRepository;
import com.portfolio.invest.domain.eval.EvalRunRow;
import com.portfolio.invest.domain.eval.PromptAssetVersion;
import com.portfolio.invest.domain.eval.PromptAssetVersionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 收割器单元守护（MS-30 B4，设计规格 §2.4 收割五步 + §3.2 告警）：报告 JSON 原始计数组装
 * RunResult（严禁直灌 eval_run.total_fail 合并列——ERROR 双计即假告警）；ERROR 并入
 * total_fail 落库但单列 total_error 留痕；PARTIAL（报告 completeness/非零退出/超时）不可
 * 为 baseline 候选；runMeta.evalAssets 回流 upsert（跳过 QUESTION_BANK，逐资产隔离撞
 * UNIQUE 不中断整批）；先判后写（alert_status/verdict_reasons 由 judge 结论驱动）；
 * DEGRADED 红卡/RECOVERED 蓝卡（飞书失败邮件降级沿 PrincipleAlertService 形态）。
 * 依赖全 mock，报告 fixture 为磁盘真文件（schema v2 结构与 ReportWriter 对齐）。
 */
class EvalHarvesterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-10-09T01:30:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final String BANK_HASH =
            "5f4dcc3b5aa765d61d8327deb882cf99a1b3c2d4e5f60718293a4b5c6d7e8f90";

    private final EvalRunRepository runRepository = mock(EvalRunRepository.class);
    private final PromptVersionRegistrar registrar = mock(PromptVersionRegistrar.class);
    private final PromptAssetVersionRepository assetVersions =
            mock(PromptAssetVersionRepository.class);
    private final AlertNotifier notifier = mock(AlertNotifier.class);
    private final MailSender mailSender = mock(MailSender.class);
    private final EvalRegressionJudge judge = mock(EvalRegressionJudge.class);

    private InvestProperties props;
    private EvalHarvester harvester;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        props = new InvestProperties();
        when(notifier.send(anyString(), anyString(), any())).thenReturn(true);
        when(mailSender.enabled()).thenReturn(false);
        when(judge.judge(any(), any()))
                .thenReturn(new JudgeVerdict(JudgeVerdict.Status.NONE, List.of("全部阈值未命中，无回归")));
        when(assetVersions.latestSnapshot()).thenReturn(List.of(
                new PromptAssetVersion(1L, PromptAssetVersion.TYPE_SYSTEM_PROMPT,
                        "system.invest", 3, "hash-sys", null, null),
                new PromptAssetVersion(2L, PromptAssetVersion.TYPE_EVAL_RUBRIC,
                        "rubric.answer-quality", 1, "hash-rubric", null, null)));
        harvester = new EvalHarvester(runRepository, registrar, assetVersions,
                notifier, mailSender, props, FIXED, judge);
    }

    // ———— 报告 fixture（结构与 ReportWriter schema v2 对齐） ————

    private Path writeReport(int pass, int fail, int error, String completeness) throws Exception {
        return writeReport("eval-report.json", pass, fail, error, completeness);
    }

    private Path writeReport(String filename, int pass, int fail, int error, String completeness)
            throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("schema", "eval-agent-report/2");
        ObjectNode runMeta = root.putObject("runMeta");
        runMeta.put("runId", "uuid-fixture");
        runMeta.put("startedAt", "2026-10-09T01:00:00+08:00[Asia/Shanghai]");
        runMeta.put("finishedAt", "2026-10-09T01:25:00+08:00[Asia/Shanghai]");
        runMeta.put("triggeredBy", "MANUAL");
        ArrayNode assetHashes = runMeta.putArray("assetHashes");
        assetHashes.add(asset("EVAL_RUBRIC", "rubric.answer-quality", "hash-rubric"));
        assetHashes.add(asset("QUESTION_BANK", "question_bank", BANK_HASH));
        ArrayNode evalAssets = runMeta.putArray("evalAssets");
        evalAssets.add(asset("EVAL_RUBRIC", "rubric.answer-quality", "hash-rubric"));
        evalAssets.add(asset("EVAL_RUBRIC", "rubric.extraction", "hash-rubric-2"));
        evalAssets.add(asset("QUESTION_BANK", "question_bank.single-turn", BANK_HASH));
        runMeta.put("questionBankHash", BANK_HASH);
        runMeta.put("totalDurationMs", 900_000);
        runMeta.put("completeness", completeness);
        ObjectNode summary = root.putObject("summary");
        summary.put("total", pass + fail + error);
        summary.put("pass", pass);
        summary.put("fail", fail);
        summary.put("skipped", 0);
        summary.put("error", error);
        ArrayNode questions = root.putArray("questions");
        for (int i = 0; i < pass; i++) {
            questions.add(question("q.pass" + i, "MARKET_FACT", "PASS"));
        }
        for (int i = 0; i < fail; i++) {
            questions.add(question("q.fail" + i, "METRIC_CALC", "FAIL"));
        }
        for (int i = 0; i < error; i++) {
            questions.add(question("q.err" + i, "HALLUCINATION_INDUCTION", "ERROR"));
        }
        Path report = dir.resolve(filename);
        Files.writeString(report, JSON.writeValueAsString(root));
        return report;
    }

    private static ObjectNode asset(String type, String key, String hash) {
        ObjectNode node = JSON.createObjectNode();
        node.put("assetType", type);
        node.put("assetKey", key);
        node.put("contentHash", hash);
        return node;
    }

    private static ObjectNode question(String id, String category, String status) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", id);
        node.put("category", category);
        node.put("mode", "stub");
        node.put("status", status);
        return node;
    }

    private EvalRunRow completedRow(long id, String alertStatus) {
        return new EvalRunRow(id, "SCHEDULED", "COMPLETED", Instant.now(), Instant.now(),
                90, 10, 4, Map.of("MARKET_FACT", new int[]{45, 5, 2}),
                Map.of("rubric.answer-quality", 1, "system.invest", 3),
                BANK_HASH, alertStatus, true, false, List.of(), 600_000L, null);
    }

    private RunResult capturedCurrent() {
        ArgumentCaptor<RunResult> captor = ArgumentCaptor.forClass(RunResult.class);
        verify(judge).judge(captor.capture(), any());
        return captor.getValue();
    }

    private EvalRunHarvest capturedPatch() {
        ArgumentCaptor<EvalRunHarvest> captor = ArgumentCaptor.forClass(EvalRunHarvest.class);
        verify(runRepository).updateHarvested(anyLong(), captor.capture());
        return captor.getValue();
    }

    // ———— 口径：原始计数组装 / ERROR 并列 / PARTIAL ————

    @Test
    @DisplayName("给定报告 error=6 fail=0，when收割，then判定输入为原始计数、落库 total_fail=6 含 ERROR 且 total_error=6 单列")
    void givenReportWithError_whenHarvest_thenRawCountsToJudgeAndMergedFailColumn() throws Exception {
        Path report = writeReport(14, 0, 6, "FULL");

        harvester.harvest(7L, report, 0);

        // 判定输入：报告原始计数（严禁预合并——直灌 DB 合并列会 ERROR 双计假告警）
        RunResult current = capturedCurrent();
        assertThat(current.totalPass()).isEqualTo(14);
        assertThat(current.totalFail()).isZero();
        assertThat(current.totalError()).isEqualTo(6);
        assertThat(current.completeness()).isEqualTo(1.0);
        assertThat(current.questionBankHash()).isEqualTo(BANK_HASH);
        assertThat(current.byCategory().get("MARKET_FACT")).containsExactly(14, 0, 0);
        assertThat(current.byCategory().get("HALLUCINATION_INDUCTION")).containsExactly(0, 0, 6);
        assertThat(current.outcomes()).hasSize(20);
        assertThat(current.outcomes().stream().filter(o -> o.id().equals("q.err0")).findFirst().orElseThrow().pass())
                .isFalse();
        // 落库口径：total_fail 含 ERROR（DDL 注释），total_error 单列留痕
        EvalRunHarvest patch = capturedPatch();
        assertThat(patch.totalPass()).isEqualTo(14);
        assertThat(patch.totalFail()).isEqualTo(6);
        assertThat(patch.totalError()).isEqualTo(6);
        assertThat(patch.status()).isEqualTo("COMPLETED");
        assertThat(patch.durationMs()).isEqualTo(900_000L);
        assertThat(patch.promptVersions())
                .containsEntry("system.invest", 3)
                .containsEntry("rubric.answer-quality", 1);
        // 报告归档为 per-run 文件（eval_run.report_path 指向归档位，防后续轮次覆盖同名字报告）
        assertThat(patch.reportPath()).contains("eval-report-7.json");
    }

    @Test
    @DisplayName("给定报告 completeness=PARTIAL，when收割，then状态 PARTIAL 且不可为 baseline 候选（即便判定恢复）")
    void givenPartialCompleteness_whenHarvest_thenStatusPartialAndNotBaselineCandidate() throws Exception {
        Path report = writeReport(14, 0, 6, "PARTIAL");
        when(judge.judge(any(), any())).thenReturn(
                new JudgeVerdict(JudgeVerdict.Status.RECOVERED, List.of("恢复")));

        harvester.harvest(7L, report, 0);

        EvalRunHarvest patch = capturedPatch();
        assertThat(patch.status()).isEqualTo("PARTIAL");
        assertThat(patch.baselineCandidate()).isFalse();
        // PARTIAL 恢复不发恢复卡（数字不可靠，baseline_candidate 未置位则「待人工确认」不成立）
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("给定非零退出码，when收割，then状态 PARTIAL（超时/异常退出同口径）")
    void givenNonZeroExit_whenHarvest_thenStatusPartial() throws Exception {
        Path report = writeReport(20, 0, 0, "FULL");

        harvester.harvest(7L, report, 137, true);

        assertThat(capturedPatch().status()).isEqualTo("PARTIAL");
    }

    @Test
    @DisplayName("给定三参收割入口，when调用，then按未超时口径委托（exitCode=0 且 FULL → COMPLETED）")
    void givenThreeArgHarvest_whenCalled_thenDelegatesUntimed() throws Exception {
        Path report = writeReport(20, 0, 0, "FULL");

        harvester.harvest(7L, report, 0);

        assertThat(capturedPatch().status()).isEqualTo("COMPLETED");
    }

    // ———— 资产回流 ————

    @Test
    @DisplayName("给定 runMeta.evalAssets，when收割，then rubric 逐条 upsert 且跳过 QUESTION_BANK（不入版本表）")
    void givenEvalAssetsInRunMeta_whenHarvest_thenUpsertsRubricAndSkipsQuestionBank() throws Exception {
        Path report = writeReport(20, 0, 0, "FULL");

        harvester.harvest(7L, report, 0);

        verify(registrar).upsert("EVAL_RUBRIC", "rubric.answer-quality", "hash-rubric");
        verify(registrar).upsert("EVAL_RUBRIC", "rubric.extraction", "hash-rubric-2");
        verify(registrar, times(2)).upsert(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("给定单资产撞 UNIQUE 约束，when收割，then降级 ERROR 日志继续整批（不中断回流）")
    void givenOneAssetUpsertViolatesUnique_whenHarvest_thenContinuesRemaining() throws Exception {
        Path report = writeReport(20, 0, 0, "FULL");
        when(registrar.upsert(eq("EVAL_RUBRIC"), eq("rubric.answer-quality"), anyString()))
                .thenThrow(new DataIntegrityViolationException("并发双写撞 UNIQUE"));

        harvester.harvest(7L, report, 0);

        verify(registrar).upsert("EVAL_RUBRIC", "rubric.extraction", "hash-rubric-2");
        verify(runRepository).updateHarvested(anyLong(), any());
    }

    // ———— 判定与告警 ————

    @Test
    @DisplayName("给定判定 DEGRADED，when收割，then飞书红卡携带降幅行与运行/基准指纹且库表留痕")
    void givenJudgeDegraded_whenHarvest_thenSendsRedAlertWithReasonLines() throws Exception {
        Path report = writeReport(16, 4, 0, "FULL");
        when(judge.judge(any(), any())).thenReturn(new JudgeVerdict(JudgeVerdict.Status.DEGRADED,
                List.of("总通过率 100.00%→80.00%（降 20.00pp ≥ 阈值 10pp）",
                        "PASS→FAIL 翻转 4 题（≥ 阈值 3）：q.fail0、q.fail1、q.fail2、q.fail3")));

        harvester.harvest(7L, report, 0);

        EvalRunHarvest patch = capturedPatch();
        assertThat(patch.alertStatus()).isEqualTo("DEGRADED");
        assertThat(patch.verdictReasons()).hasSize(2);
        ArgumentCaptor<List<String>> lines = ArgumentCaptor.forClass((Class) List.class);
        verify(notifier).send(eq("【eval 回归告警】2026-10-09"), eq("red"), lines.capture());
        assertThat(lines.getValue())
                .contains("总通过率 100.00%→80.00%（降 20.00pp ≥ 阈值 10pp）",
                        "PASS→FAIL 翻转 4 题（≥ 阈值 3）：q.fail0、q.fail1、q.fail2、q.fail3");
        assertThat(lines.getValue()).anyMatch(l -> l.contains("eval_run#7"));
        assertThat(lines.getValue()).anyMatch(l -> l.contains(BANK_HASH.substring(0, 8)));
        verify(mailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("给定飞书推送失败且邮件已配置，when劣化收割，then邮件降级兜底（沿 PrincipleAlertService 形态）")
    void givenNotifierFails_whenDegraded_thenMailFallback() throws Exception {
        Path report = writeReport(16, 4, 0, "FULL");
        when(judge.judge(any(), any())).thenReturn(new JudgeVerdict(JudgeVerdict.Status.DEGRADED,
                List.of("总通过率降幅命中")));
        when(notifier.send(anyString(), anyString(), any())).thenReturn(false);
        when(mailSender.enabled()).thenReturn(true);
        props.getMail().getAlertMailTo().add("ops@example.com");

        harvester.harvest(7L, report, 0);

        verify(mailSender).send(eq("ops@example.com"),
                eq("[invest 告警] 【eval 回归告警】2026-10-09"), anyString());
    }

    @Test
    @DisplayName("给定判定 RECOVERED 且本跑 COMPLETED，when收割，then蓝卡 + baseline_candidate=true")
    void givenJudgeRecoveredAndCompleted_whenHarvest_thenBlueCardAndBaselineCandidate() throws Exception {
        Path report = writeReport(20, 0, 0, "FULL");
        when(judge.judge(any(), any())).thenReturn(new JudgeVerdict(JudgeVerdict.Status.RECOVERED,
                List.of("全部阈值未命中，相对回归已恢复（baseline_candidate 待人工确认）")));

        harvester.harvest(7L, report, 0);

        EvalRunHarvest patch = capturedPatch();
        assertThat(patch.alertStatus()).isEqualTo("RECOVERED");
        assertThat(patch.baselineCandidate()).isTrue();
        ArgumentCaptor<List<String>> lines = ArgumentCaptor.forClass((Class) List.class);
        verify(notifier).send(eq("【eval 回归恢复】2026-10-09"), eq("blue"), lines.capture());
        assertThat(lines.getValue()).anyMatch(l -> l.contains("baseline_candidate=true 待人工确认"));
        assertThat(lines.getValue()).anyMatch(l -> l.contains("100.00%"));
    }

    @Test
    @DisplayName("给定无 baseline（首跑），when收割，then alert_status=NONE、理由留痕、不发告警")
    void givenNoBaseline_whenHarvest_thenAlertStatusNoneWithoutNotification() throws Exception {
        Path report = writeReport(20, 0, 0, "FULL");
        when(runRepository.findBaseline()).thenReturn(Optional.empty());
        when(judge.judge(any(), any())).thenReturn(
                new JudgeVerdict(JudgeVerdict.Status.NO_BASELINE, List.of("无 baseline（首跑或基准变更后），不判相对回归")));

        harvester.harvest(7L, report, 0);

        EvalRunHarvest patch = capturedPatch();
        assertThat(patch.alertStatus()).isEqualTo("NONE");
        assertThat(patch.verdictReasons()).singleElement().asString().contains("无 baseline");
        verifyNoInteractions(notifier);
    }

    // ———— 历史组装 ————

    @Test
    @DisplayName("给定上一跑 DEGRADED 且有基准行，when收割，then prevRunDegraded 入参且基准为原始计数重建")
    void givenPreviousDegradedAndBaselineRow_whenHarvest_thenHistoryFedToJudge() throws Exception {
        Path report = writeReport(20, 0, 0, "FULL");
        when(runRepository.findBaseline()).thenReturn(Optional.of(completedRow(3L, "NONE")));
        when(runRepository.findLatestExcluding(7L))
                .thenReturn(Optional.of(completedRow(6L, "DEGRADED")));

        harvester.harvest(7L, report, 0);

        RunResult current = capturedCurrent();
        assertThat(current.prevRunDegraded()).isTrue();
        assertThat(current.rubricVersions()).containsEntry("rubric.answer-quality", "1");
        ArgumentCaptor<Optional<RunResult>> baseline =
                ArgumentCaptor.forClass((Class) Optional.class);
        verify(judge).judge(any(), baseline.capture());
        RunResult base = baseline.getValue().orElseThrow();
        // 基准行 total_fail=10 为含 ERROR 合并列：重建剥出原始 fail=10-4=6，error=4 单列
        assertThat(base.totalPass()).isEqualTo(90);
        assertThat(base.totalFail()).isEqualTo(6);
        assertThat(base.totalError()).isEqualTo(4);
        assertThat(base.questionBankHash()).isEqualTo(BANK_HASH);
        assertThat(base.rubricVersions()).containsEntry("rubric.answer-quality", "1");
    }

    @Test
    @DisplayName("给定基准行带 v2 报告文件，when收割，then基准题目明细自文件装载（翻转判定可用）")
    void givenBaselineRowWithReportFile_whenHarvest_thenBaselineOutcomesFromFile() throws Exception {
        Path report = writeReport(20, 0, 0, "FULL");
        // 基准报告另写一份：18 PASS + 2 FAIL（q.fail0/q.fail1 在基准侧为 FAIL）——明细以文件为准
        Path baselineReport = writeReport("baseline-report.json", 18, 2, 0, "FULL");
        when(runRepository.findBaseline()).thenReturn(Optional.of(
                new EvalRunRow(3L, "SCHEDULED", "COMPLETED", Instant.now(), Instant.now(),
                        18, 2, 0, Map.of(), Map.of("rubric.answer-quality", 1), BANK_HASH,
                        "NONE", true, false, List.of(), 600_000L, baselineReport.toString())));

        harvester.harvest(7L, report, 0);

        ArgumentCaptor<Optional<RunResult>> baseline =
                ArgumentCaptor.forClass((Class) Optional.class);
        verify(judge).judge(any(), baseline.capture());
        RunResult base = baseline.getValue().orElseThrow();
        assertThat(base.outcomes()).hasSize(20);
        // 文件装载证明：库行列不含题目明细（DB 回退为空 outcomes），q.pass0/q.fail0 逐题映射文件状态
        assertThat(base.outcomes().stream().filter(o -> o.id().equals("q.pass0"))
                .findFirst().orElseThrow().pass()).isTrue();
        assertThat(base.outcomes().stream().filter(o -> o.id().equals("q.fail0"))
                .findFirst().orElseThrow().pass()).isFalse();
        // 基准 totals 同样取报告文件原始计数（非库行合并列）
        assertThat(base.totalPass()).isEqualTo(18);
        assertThat(base.totalFail()).isEqualTo(2);
        assertThat(base.totalError()).isZero();
    }

    // ———— 兜底 ————

    @Test
    @DisplayName("给定报告缺失，when收割，then行标 FAILED 不落数字不发告警")
    void givenReportMissing_whenHarvest_thenRowMarkedFailed() throws Exception {
        Path missing = dir.resolve("nope/eval-report.json");

        harvester.harvest(7L, missing, 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> reasons = ArgumentCaptor.forClass((Class) List.class);
        verify(runRepository).markFailed(eq(7L), reasons.capture());
        assertThat(reasons.getValue().get(0)).contains("报告缺失");
        verify(runRepository, never()).updateHarvested(anyLong(), any());
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("给定收割中途异常，when收割，then兜底标 FAILED 不向上抛")
    void givenHarvestExplodes_whenHarvest_thenRowMarkedFailedWithoutThrowing() throws Exception {
        Path report = writeReport(20, 0, 0, "FULL");
        when(registrar.upsert(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("库不可用"));

        org.assertj.core.api.Assertions.assertThatCode(() -> harvester.harvest(7L, report, 0))
                .doesNotThrowAnyException();
        verify(runRepository).markFailed(eq(7L), any());
    }
}

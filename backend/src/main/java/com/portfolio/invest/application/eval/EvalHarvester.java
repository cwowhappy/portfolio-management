package com.portfolio.invest.application.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.application.alert.AlertNotifier;
import com.portfolio.invest.application.auth.MailSender;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.eval.EvalRunHarvest;
import com.portfolio.invest.domain.eval.EvalRunRepository;
import com.portfolio.invest.domain.eval.EvalRunRow;
import com.portfolio.invest.domain.eval.PromptAssetVersion;
import com.portfolio.invest.domain.eval.PromptAssetVersionRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 评测报告收割器（MS-30 B4，设计规格 §2.4 收割五步，生产上下文内执行）：子进程结束后读
 * 报告 JSON → eval_run 落库（RUNNING 行 update 终态）→ runMeta.evalAssets 回流 upsert
 * （EVAL_RUBRIC 逐条、跳过 QUESTION_BANK）→ prompt_versions 版本快照 → 相对回归判定
 * （{@link EvalRegressionJudge}，先判后写）→ 告警发送（DEGRADED 红卡/RECOVERED 蓝卡，
 * 飞书失败邮件降级沿 PrincipleAlertService 形态）→ 恢复跑置 baseline_candidate。
 *
 * <p>三条硬口径（Task 5 传导）：
 * <ol>
 *   <li>RunResult totals 从报告 JSON 原始计数组装，<b>严禁直灌 eval_run 合并列</b>——
 *       total_fail 是「含 ERROR」口径（DDL 注释），直灌则 ERROR 双计→通过率虚低→假告警；
 *       落库侧另按合并口径写列（total_fail = 原始 fail + error，total_error 单列留痕）；</li>
 *   <li>prevRunDegraded 从上一行 eval_run.alert_status 读出（=='DEGRADED'）装入本跑
 *       RunResult——恢复判定的唯一输入通道；</li>
 *   <li>judge 调用先于 eval_run 最终 update（先判后写 alert_status/verdict_reasons）。</li>
 * </ol>
 *
 * <p>终态映射：超时强杀且报告未产出 → PARTIAL（无数字、理由留痕——§2.2.4 超时收割按
 * PARTIAL 语义，审查 I1）；报告缺失/不可解析（非超时）→ FAILED；超时/非零退出/报告
 * completeness=PARTIAL → PARTIAL（不可为 baseline 候选；token 预算超限中止由 runner 写
 * completeness=PARTIAL + tokenBudgetExceeded，收割侧追加理由行进 verdict_reasons）；
 * 其余 → COMPLETED。报告归档为
 * per-run 副本（eval-report-&lt;runId&gt;.json）后入库——防后续轮次覆盖同名字报告导致基准行
 * 读到别跑数据。判定五态落 alert_status 时 INCOMPARABLE/NO_BASELINE 收敛为 NONE（值域 3 值），
 * 理由全量留 verdict_reasons。收割尽力而为：任何异常兜底标 FAILED 不向上抛（调度看护侧只管互斥旗）。
 *
 * <p><b>两轨兼容（MS-30 跟进②）</b>：按报告 schema 分轨解析——{@code eval-agent-report/2}
 * 走上述对话轨链路；{@code eval-extraction-report/3}（无 runMeta）走抽取轨分支：totals 从
 * 其 summary 读（pass=overallPass、error 单列、原始 fail = total−overallPass−error）、
 * byCategory 聚合 <b>EXTRACT 单类</b>（news/announcement/policy 三 kind 不入 judge 的
 * 分类词表，与「抽取/对话老题仅参与总轨」口径一致）、<b>不判回归不任 baseline</b>（v1 简化：
 * 抽取无 baseline 链，alert_status 恒 NONE、verdict_reasons 注明；token 护栏对抽取轨
 * 不适用——抽取 runner 无题级 tokenUsage 累计通道，一并注明）、prompt_versions 仍取登记表
 * 最新快照（intel.* 提示词的版本锚消费通道）、durationMs 取逐题汇总、报告归档同通道。
 *
 * <p>eval 子进程（--Eval_MODE=true）不注册本类（类级条件沿 SchedulingConfig 先例）——审查
 * C1：本类无条件 @Service 强依赖门控缺席的 {@link PromptVersionRegistrar}（+调度器又依赖本类），
 * 曾致子进程上下文装配必死。守护见 EvalModeBeanGatingTest / EvalModeContextGuardIntegrationTest。
 */
@Service
@ConditionalOnExpression("!'true'.equals('${Eval_MODE:}')")
public class EvalHarvester {

    private static final Logger log = LoggerFactory.getLogger(EvalHarvester.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 报告内题库聚合类型（不入 prompt_asset_version——题库 hash 走 eval_run.question_bank_hash）。 */
    private static final String TYPE_QUESTION_BANK = "QUESTION_BANK";

    private final EvalRunRepository runRepository;
    private final PromptVersionRegistrar registrar;
    private final PromptAssetVersionRepository assetVersions;
    private final AlertNotifier notifier;
    private final MailSender mailSender;
    private final InvestProperties props;
    private final Clock clock;
    private final EvalRegressionJudge judge;

    /** 主构造器（@Autowired：存在测试专用重载构造器时需显式指定注入入口，沿 PrincipleAlertService 先例）。 */
    @Autowired
    public EvalHarvester(EvalRunRepository runRepository, PromptVersionRegistrar registrar,
                         PromptAssetVersionRepository assetVersions, AlertNotifier notifier,
                         MailSender mailSender, InvestProperties props) {
        this(runRepository, registrar, assetVersions, notifier, mailSender, props,
                Clock.system(ZoneId.of("Asia/Shanghai")),
                new EvalRegressionJudge(props.getEval().getRegression().getPassRateDropPp(),
                        props.getEval().getRegression().getFlipThreshold(),
                        props.getEval().getRegression().getCategoryDropPp()));
    }

    /** 测试便利构造：注入时钟与判定器（阈值接线由主构造器从 invest.eval.regression.* 装配）。 */
    EvalHarvester(EvalRunRepository runRepository, PromptVersionRegistrar registrar,
                  PromptAssetVersionRepository assetVersions, AlertNotifier notifier,
                  MailSender mailSender, InvestProperties props,
                  Clock clock, EvalRegressionJudge judge) {
        this.runRepository = runRepository;
        this.registrar = registrar;
        this.assetVersions = assetVersions;
        this.notifier = notifier;
        this.mailSender = mailSender;
        this.props = props;
        this.clock = clock;
        this.judge = judge;
    }

    /** 收割入口（brief 契约签名）：未超时口径委托。 */
    public void harvest(long runId, Path report, int exitCode) {
        harvest(runId, report, exitCode, false);
    }

    /**
     * 收割入口（调度看护调用）：timedOut 为超时强杀标记（§2.2.4——超时收割按 PARTIAL 语义，
     * 报告可能缺失/残缺）。任何内部异常兜底标 FAILED，绝不向上抛。
     */
    public void harvest(long runId, Path report, int exitCode, boolean timedOut) {
        try {
            doHarvest(runId, report, exitCode, timedOut);
        } catch (Exception e) { // 兜底：收割自身失效不得卡死调度看护（互斥旗在调度侧 finally 释放）
            log.error("eval 收割异常（runId={}）", runId, e);
            try {
                runRepository.markFailed(runId,
                        List.of("收割异常: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            } catch (Exception mark) {
                log.error("eval 收割兜底标 FAILED 失败（runId={}）", runId, mark);
            }
        }
    }

    private void doHarvest(long runId, Path reportPath, int exitCode, boolean timedOut) {
        JsonNode report = readReport(reportPath);
        if (report == null) {
            if (timedOut) { // 审查 I1：超时强杀优先于 FAILED——§2.2.4 超时收割按 PARTIAL 语义（无数字、理由留痕）
                runRepository.updateHarvested(runId, new EvalRunHarvest(EvalRunRow.STATUS_PARTIAL,
                        Instant.now(clock), 0, 0, 0, Map.of(), Map.of(), null,
                        EvalRunRow.ALERT_NONE, false,
                        List.of("超时强杀且报告未产出: " + reportPath + "（exitCode=" + exitCode + "）"),
                        null, reportPath.toString()));
                log.warn("eval 超时强杀收割（无报告）：runId={} 落 PARTIAL", runId);
                return;
            }
            runRepository.markFailed(runId, List.of("评测报告缺失或不可解析: " + reportPath
                    + "（exitCode=" + exitCode + "）"));
            return;
        }

        // —— 轨道分派（MS-30 跟进②）：抽取轨报告 schema（无 runMeta）走独立分支，不进判定链 ——
        if (report.path("schema").asText("").startsWith("eval-extraction-report/")) {
            harvestExtraction(runId, reportPath, report, exitCode, timedOut);
            return;
        }

        // —— ① 原始计数（报告 JSON 直读，judge 输入的唯一口径） ——
        JsonNode summary = report.path("summary");
        JsonNode runMeta = report.path("runMeta");
        int pass = summary.path("pass").asInt(0);
        int fail = summary.path("fail").asInt(0);
        int error = summary.path("error").asInt(0);
        int total = summary.path("total").asInt(pass + fail + error);
        JsonNode questions = report.path("questions");
        Map<String, int[]> byCategory = byCategory(questions);
        String questionBankHash = textOrNull(runMeta.path("questionBankHash"));

        // —— ② 资产回流：runMeta.evalAssets 逐条 upsert（rubric 等入库类型；QUESTION_BANK 跳过），
        //     逐资产隔离——单资产撞 UNIQUE（并发双写）降级 ERROR 日志继续整批（Task 4 M2 裁定） ——
        upsertEvalAssets(runMeta.path("evalAssets"));

        // —— ③ 版本快照（回流后登记表每键最新）与可比性指纹 rubric 子集 ——
        Map<String, Integer> promptVersions = snapshotVersions();

        // —— ④ 历史组装：基准行 + 历史序上一跑（prevRunDegraded 恢复判定输入） ——
        Optional<EvalRunRow> baselineRow = runRepository.findBaseline();
        boolean prevRunDegraded = runRepository.findLatestExcluding(runId)
                .map(row -> EvalRunRow.ALERT_DEGRADED.equals(row.alertStatus()))
                .orElse(false);

        // —— ⑤ 判定（先判后写）：completeness = 产出终态题数（pass+fail+error）/ 应跑题数
        //     （SKIPPED 未产出终态不计分子；仅不可比分支的完成率告警消费它） ——
        double completeness = total > 0 ? (pass + fail + error) / (double) total : 1.0;
        RunResult current = new RunResult(completeness, pass, fail, error, byCategory,
                questionBankHash, rubricVersions(promptVersions), outcomes(questions), prevRunDegraded);
        Optional<RunResult> baselineResult = baselineRow.map(this::baselineResult);
        JudgeVerdict verdict = judge.judge(current, baselineResult);

        // —— ⑥ 终态与告警映射 ——
        String status = timedOut || exitCode != 0
                || "PARTIAL".equals(runMeta.path("completeness").asText())
                ? EvalRunRow.STATUS_PARTIAL : EvalRunRow.STATUS_COMPLETED;
        String alertStatus = switch (verdict.status()) {
            case DEGRADED -> EvalRunRow.ALERT_DEGRADED;
            case RECOVERED -> EvalRunRow.ALERT_RECOVERED;
            default -> EvalRunRow.ALERT_NONE; // NONE/NO_BASELINE/INCOMPARABLE 收敛留痕
        };
        boolean baselineCandidate = verdict.status() == JudgeVerdict.Status.RECOVERED
                && EvalRunRow.STATUS_COMPLETED.equals(status);

        // token 预算超限中止（终审 I-1 接线）：PARTIAL 语义经 completeness 通道已覆盖（不任
        // baseline），此处只补「为什么 PARTIAL」的人读理由行追加进 verdict_reasons，不新增列
        List<String> reasons = new ArrayList<>(verdict.reasons());
        if (runMeta.path("tokenBudgetExceeded").asBoolean(false)) {
            reasons.add("token 预算超限中止: 剩余题未跑即中止（invest.eval.token-budget，不任 baseline 候选）");
        }

        // —— ⑦ 落库（judge 结论后写；total_fail 落合并口径 = 原始 fail + error） + 报告归档 ——
        Path archived = archiveReport(runId, reportPath);
        runRepository.updateHarvested(runId, new EvalRunHarvest(status, Instant.now(clock),
                pass, fail + error, error, byCategory, promptVersions, questionBankHash,
                alertStatus, baselineCandidate, reasons,
                runMeta.path("totalDurationMs").isNumber() ? runMeta.path("totalDurationMs").asLong() : null,
                archived.toString()));

        // —— ⑧ 告警（留痕以库表为准 NFR-7，飞书/邮件是通知通道；恢复卡只在候选置位时发——
        //     PARTIAL 恢复数字不可靠，「待人工确认」不成立） ——
        if (EvalRunRow.ALERT_DEGRADED.equals(alertStatus)) {
            sendDegradedAlert(runId, verdict, questionBankHash, promptVersions, pass, fail, error);
        } else if (baselineCandidate) {
            sendRecoveredAlert(runId, verdict, pass, fail, error);
        }
        log.info("eval 收割完成：runId={} status={} alert={}（pass={} fail={}（含 error={}）判定={}",
                runId, status, alertStatus, pass, fail + error, error, verdict.status());
    }

    // ———— 抽取轨收割（MS-30 跟进②，schema=eval-extraction-report/3，无 runMeta） ————

    /**
     * 抽取轨分支：totals 从其 summary 读（pass=overallPass、error 单列留痕、原始 fail
     * = total−overallPass−error）、byCategory 聚合 EXTRACT 单类、不判回归不任 baseline
     * （v1 简化：抽取无 baseline 链——alert_status 恒 NONE，理由注明不参与判定与 token
     * 护栏不适用）、prompt_versions 取登记表最新快照（intel.* 提示词版本锚）、durationMs
     * 逐题汇总、报告归档同通道。SKIP（缺 key 未跑）/ERROR（框架异常）报告无 summary：
     * SKIP → PARTIAL 理由留痕；ERROR → FAILED（沿对话轨「报告不可用」口径）。
     */
    private void harvestExtraction(long runId, Path reportPath, JsonNode report,
                                   int exitCode, boolean timedOut) {
        String reportStatus = report.path("status").asText("");
        if ("SKIP".equals(reportStatus)) {
            runRepository.updateHarvested(runId, new EvalRunHarvest(EvalRunRow.STATUS_PARTIAL,
                    Instant.now(clock), 0, 0, 0, Map.of(), Map.of(), null,
                    EvalRunRow.ALERT_NONE, false,
                    List.of("抽取轨 SKIP 未跑: " + report.path("skipReason").asText("（无理由）")),
                    null, reportPath.toString()));
            log.warn("eval 抽取轨 SKIP 收割：runId={} 落 PARTIAL", runId);
            return;
        }
        if ("ERROR".equals(reportStatus)) {
            runRepository.markFailed(runId, List.of("抽取轨框架异常（报告 status=ERROR）: "
                    + report.path("error").asText("（无明细）")));
            log.error("eval 抽取轨框架异常收割：runId={} 落 FAILED", runId);
            return;
        }
        JsonNode summary = report.path("summary");
        int total = summary.path("total").asInt(0);
        int pass = summary.path("overallPass").asInt(0);
        int error = summary.path("error").asInt(0);
        int fail = Math.max(0, total - pass - error); // parseFailed/llmUnavailable/维度 FAIL 合计
        Map<String, int[]> byCategory = Map.of("EXTRACT", new int[]{pass, fail, error});
        String status = timedOut || exitCode != 0 ? EvalRunRow.STATUS_PARTIAL : EvalRunRow.STATUS_COMPLETED;
        List<String> reasons = List.of(
                "EXTRACT 轨不参与回归判定（v1 语义：无 baseline 链，落库留痕不判劣化/恢复）",
                "token 护栏不适用于 EXTRACT 轨（抽取 runner 无题级 tokenUsage 累计通道）");
        // 版本快照仍取登记表最新（报告无 runMeta 无回流通道；intel.* 抽取提示词的版本锚消费位）
        Map<String, Integer> promptVersions = snapshotVersions();
        // 报告无 runMeta.totalDurationMs：逐题 durationMs 汇总（无题目时留 null）
        long durationSum = 0;
        boolean anyQuestion = false;
        for (JsonNode question : report.path("questions")) {
            durationSum += question.path("durationMs").asLong(0);
            anyQuestion = true;
        }
        Path archived = archiveReport(runId, reportPath);
        runRepository.updateHarvested(runId, new EvalRunHarvest(status, Instant.now(clock),
                pass, fail + error, error, byCategory, promptVersions, null,
                EvalRunRow.ALERT_NONE, false, reasons,
                anyQuestion ? durationSum : null, archived.toString()));
        log.info("eval 抽取轨收割完成：runId={} status={}（pass={} fail={}（含 error={}），不参与判定）",
                runId, status, pass, fail + error, error);
    }

    // ———— 历史组装 ————

    /**
     * 基准行 → RunResult：报告文件优先（题目明细在列——翻转判定可用），缺失时按库行列重建
     * （total_fail 为含 ERROR 合并列，剥出原始 fail = total_fail − total_error；outcomes 空
     * → 翻转判定保守跳过）。rubric 版本集两侧统一取 prompt_versions 快照子集（版本号口径，
     * 同内容恒同版本——与内容 hash 等价的比较基）。
     */
    private RunResult baselineResult(EvalRunRow row) {
        JsonNode report = row.reportPath() == null ? null : readReport(Path.of(row.reportPath()));
        if (report != null && !report.path("runMeta").isMissingNode()) {
            JsonNode summary = report.path("summary");
            return new RunResult(1.0, summary.path("pass").asInt(0), summary.path("fail").asInt(0),
                    summary.path("error").asInt(0), byCategory(report.path("questions")),
                    textOrNull(report.path("runMeta").path("questionBankHash")),
                    rubricVersions(row.promptVersions()), outcomes(report.path("questions")), false);
        }
        log.info("基准行报告文件不可读，按库行列重建 RunResult（翻转判定将保守跳过）：runId={} path={}",
                row.id(), row.reportPath());
        return new RunResult(1.0, row.totalPass(), row.totalFail() - row.totalError(),
                row.totalError(), row.byCategory(), row.questionBankHash(),
                rubricVersions(row.promptVersions()), List.of(), false);
    }

    // ———— 报告装配 ————

    /** 分类 → [pass, fail, error]（原始计数；SKIPPED 未产出终态不计）。 */
    private static Map<String, int[]> byCategory(JsonNode questions) {
        Map<String, int[]> result = new LinkedHashMap<>();
        for (JsonNode question : questions) {
            String category = question.path("category").asText(null);
            if (category == null) {
                continue;
            }
            int[] counts = result.computeIfAbsent(category, key -> new int[3]);
            switch (question.path("status").asText()) {
                case "PASS" -> counts[0]++;
                case "FAIL" -> counts[1]++;
                case "ERROR" -> counts[2]++;
                default -> { } // SKIPPED 等：未产出终态，不进分类分母
            }
        }
        return result;
    }

    /** 题目级明细：pass 映射钉死 = (status==PASS)——PASS→ERROR 与 PASS→FAIL 同算翻转（决策 #10）。 */
    private static List<RunResult.QuestionOutcomeLite> outcomes(JsonNode questions) {
        List<RunResult.QuestionOutcomeLite> result = new ArrayList<>();
        for (JsonNode question : questions) {
            result.add(new RunResult.QuestionOutcomeLite(question.path("id").asText(),
                    "PASS".equals(question.path("status").asText())));
        }
        return result;
    }

    /** 版本快照（回流后登记表每键最新）：asset_key → version。 */
    private Map<String, Integer> snapshotVersions() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (PromptAssetVersion version : assetVersions.latestSnapshot()) {
            result.put(version.assetKey(), version.version());
        }
        return result;
    }

    /** 可比性指纹之二：快照中 rubric.* 子集（值转字符串形态，与 baseline 全等比对）。 */
    private static Map<String, String> rubricVersions(Map<String, Integer> promptVersions) {
        Map<String, String> result = new LinkedHashMap<>();
        promptVersions.entrySet().stream()
                .filter(e -> e.getKey().startsWith("rubric."))
                .forEach(e -> result.put(e.getKey(), String.valueOf(e.getValue())));
        return result;
    }

    /** 报告读取（缺失/不可解析返回 null → FAILED 兜底）。 */
    private JsonNode readReport(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return JSON.readTree(Files.readString(path));
        } catch (Exception e) {
            log.error("eval 报告读取/解析失败: {}", path, e);
            return null;
        }
    }

    /** 报告归档为 per-run 副本（防后续轮次覆盖同名报告——基准行需读到当跑原貌）；失败保留原路径。 */
    private Path archiveReport(long runId, Path report) {
        try {
            Path archive = report.resolveSibling("eval-report-" + runId + ".json");
            Files.copy(report, archive, StandardCopyOption.REPLACE_EXISTING);
            return archive;
        } catch (IOException e) {
            log.warn("eval 报告归档失败（原路径入库）: {}", report, e);
            return report;
        }
    }

    // ———— 资产回流 ————

    /**
     * runMeta.evalAssets 逐条 upsert（§2.4 收割第②步）：eval-only 资产（EVAL_RUBRIC）经报告
     * 回流登记，绕开「生产 bootJar 无 rubric 资源」的 classpath 边界；QUESTION_BANK 为题库
     * 聚合项不入版本表（hash 走 eval_run.question_bank_hash）。逐资产 try/catch：撞 UNIQUE
     * （并发双写）降级 ERROR 日志继续，单资产失败不得中断整批回流。
     */
    private void upsertEvalAssets(JsonNode evalAssets) {
        int registered = 0;
        int skipped = 0;
        for (JsonNode asset : evalAssets) {
            String assetType = asset.path("assetType").asText("");
            String assetKey = asset.path("assetKey").asText();
            if (TYPE_QUESTION_BANK.equals(assetType)) {
                skipped++;
                continue;
            }
            try {
                registrar.upsert(assetType, assetKey, asset.path("contentHash").asText());
                registered++;
            } catch (DataIntegrityViolationException e) {
                log.error("eval 资产回流撞唯一约束（{}:{}），跳过该项继续整批", assetType, assetKey, e);
            }
        }
        log.info("eval 资产回流完成：upsert {} 项（跳过题库聚合项 {} 个）", registered, skipped);
    }

    // ———— 告警（§3.2 文案与状态机） ————

    /** 劣化卡（红）：判定理由行直取（降幅/翻转/分类数字已在 reasons）+ 运行定位 + 基准指纹。 */
    private void sendDegradedAlert(long runId, JudgeVerdict verdict, String questionBankHash,
                                   Map<String, Integer> promptVersions, int pass, int fail, int error) {
        String title = "【eval 回归告警】" + LocalDate.now(clock);
        List<String> lines = new ArrayList<>();
        lines.add("总通过率：" + passRate(pass, fail, error));
        lines.addAll(verdict.reasons());
        lines.add("运行：eval_run#" + runId + "（明细见 eval_run 行与报告归档）");
        lines.add("基准：题库 hash " + shortHash(questionBankHash)
                + " / 提示词版本 " + versionShortCode(promptVersions));
        boolean ok = notifier.send(title, "red", lines);
        if (!ok) {
            log.warn("eval 回归告警飞书推送失败（runId={}），邮件降级", runId);
            sendAlertMail(title, lines);
        }
    }

    /** 恢复卡（蓝）：恢复时间 + 本跑通过率 + 候选待人工确认。 */
    private void sendRecoveredAlert(long runId, JudgeVerdict verdict, int pass, int fail, int error) {
        String title = "【eval 回归恢复】" + LocalDate.now(clock);
        List<String> lines = new ArrayList<>();
        lines.add("恢复时间：" + LocalDate.now(clock) + "（eval_run#" + runId + "）");
        lines.add("本跑通过率：" + passRate(pass, fail, error));
        lines.addAll(verdict.reasons());
        lines.add("baseline_candidate=true 待人工确认（PUT /api/admin/eval/runs/" + runId + "/baseline 生效）");
        boolean ok = notifier.send(title, "blue", lines);
        if (!ok) {
            log.warn("eval 回归恢复飞书推送失败（runId={}），邮件降级", runId);
            sendAlertMail(title, lines);
        }
    }

    /** 飞书告警失败的邮件降级（沿 PrincipleAlertService:137-159 形态）：SMTP 可用且配置收件人才发。 */
    private void sendAlertMail(String title, List<String> bodyLines) {
        List<String> tos = props.getMail().getAlertMailTo();
        if (!mailSender.enabled() || tos.isEmpty()) {
            return;
        }
        String subject = "[invest 告警] " + title;
        String text = title + "\n" + String.join("\n", bodyLines);
        for (String to : tos) {
            try {
                mailSender.send(to, subject, text);
            } catch (Exception e) { // 单个收件人失败不阻断其余，也绝不反向拖垮收割
                log.error("eval 告警邮件降级失败 to={}", to, e);
            }
        }
    }

    private static String passRate(int pass, int fail, int error) {
        long denominator = (long) pass + fail + error;
        return denominator <= 0 ? "N/A（空跑）"
                : String.format(Locale.ROOT, "%.2f%%", pass * 100.0 / denominator);
    }

    /**
     * 提示词版本快照短码（审查 M1）：sorted {@code key=v} 行 SHA-256 前 8 位——沿
     * {@link #shortHash} 8 位人读先例；版本全表在 eval_run.prompt_versions，告警只携指纹。
     */
    private static String versionShortCode(Map<String, Integer> promptVersions) {
        String canonical = promptVersions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** hash 短码（前 8 位）：告警人读摘要用，全量值在库表/报告。 */
    private static String shortHash(String hash) {
        return hash == null ? "未知（v1 旧档）" : hash.substring(0, Math.min(8, hash.length()));
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.asText().isBlank() ? null : node.asText();
    }
}

package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.application.alert.AlertNotifier;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.ExtractStatus;
import com.portfolio.invest.domain.intelligence.PolicyExtractResult;
import com.portfolio.invest.domain.intelligence.PolicyRecord;
import com.portfolio.invest.domain.intelligence.PolicyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 政策 LLM 结构化抽取批（F11/F12，MS-22 Task 5，照 {@link NewsExtractionService}「批量→逐条→
 * 失败隔离」骨架）：每日 09:10 一批（Asia/Shanghai，采集任务 08:40 落 raw 后；含周末——空批
 * 快退），游标窗口内（{@link #EXTRACTION_LOOKBACK_DAYS} 个自然日含当日，判定列 published_at）
 * 的 PENDING 政策按 {@code extract-batch-size} 循环取批逐条抽取方向/力度/影响领域/摘要/置信度。
 *
 * <p>isPolicy=false 兜底（F12「误收录须标注为低置信」）：LLM 判定非政策类（领导活动/会议
 * 新闻/转载/行政事务漏网）的条目由 {@link PolicyExtractor} 置换为哨兵字段<b>落库而非丢弃</b>
 * （低置信标注，P4 页面与 T6 简报可过滤）；政策事件长期保留（FK 无级联删除，无清理任务）。
 *
 * <p>失败语义（与新闻/公告同款）：单条解析失败（含重试 1 次）标 FAILED 终态；LLM 通道不可用
 * （未配置/调用失败）整批静默跳过、条目留 PENDING 下批再试（绝不误杀）；单条意外异常隔离不
 * 阻塞批次；顶层 try/catch 吞异常护调度线程。无批末推送（政策不入 F10 公告推送——政策事件
 * 进简报「宏观与政策」节由 T6 消费 {@link PolicyRepository#searchEvents}，与本批解耦）。
 *
 * <p>短文本跳过：title+正文合计 &lt; {@link #MIN_EXTRACT_INPUT_LENGTH} 的条目直接跳过
 * （政策正文都长，此为防御——不送 LLM、不落库、留 PENDING 由游标窗口自然过期）。
 * 成本护栏（D16）：每条抽取后向共享 {@link IntelligenceTokenBudget} 计入 inputTokens
 * （与新闻/公告抽取批同一 bean，跨服务共享当日额度），超限当日停批 + AlertNotifier 每日
 * 告警一次；FAILED 批末计数告警（独立按日去重，NFR-5）。幂等可重入：PENDING→SUCCESS/FAILED
 * 置换，FAILED 为终态不重试；LLM 失效/护栏停批的积压在游标窗口内由后续批次续抽。
 */
@Service
public class PolicyExtractionService {

    private static final Logger log = LoggerFactory.getLogger(PolicyExtractionService.class);

    /** 抽取游标窗口（自然日含当日）：LLM 失效/护栏停批后的积压跨日续抽上界（与新闻/公告同款）。 */
    static final int EXTRACTION_LOOKBACK_DAYS = 3;

    /** 短文本跳过阈值：title+正文合计低于此字符数不值得过 LLM（政策正文都长，纯防御）。 */
    static final int MIN_EXTRACT_INPUT_LENGTH = 50;

    private final PolicyRepository policyRepository;
    private final IntelligenceChatPort chatPort;
    private final IntelligenceTokenBudget tokenBudget;
    private final AlertNotifier alertNotifier;
    private final InvestProperties props;
    private final PolicyExtractor extractor;
    private final Clock clock;

    /** 护栏告警去重：同一自然日最多告警一次（跨日随日期比较自动失效）。 */
    private LocalDate alertedDay;

    /** 解析失败告警去重（独立于护栏告警）：同一自然日最多告警一次。 */
    private LocalDate failedAlertedDay;

    @Autowired
    public PolicyExtractionService(PolicyRepository policyRepository, IntelligenceChatPort chatPort,
                                   IntelligenceTokenBudget tokenBudget, AlertNotifier alertNotifier,
                                   InvestProperties props) {
        this(policyRepository, chatPort, tokenBudget, alertNotifier, props,
                new PolicyExtractor(), Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    /** 测试构造器：注入抽取器与时钟。 */
    PolicyExtractionService(PolicyRepository policyRepository, IntelligenceChatPort chatPort,
                            IntelligenceTokenBudget tokenBudget, AlertNotifier alertNotifier,
                            InvestProperties props, PolicyExtractor extractor, Clock clock) {
        this.policyRepository = policyRepository;
        this.chatPort = chatPort;
        this.tokenBudget = tokenBudget;
        this.alertNotifier = alertNotifier;
        this.props = props;
        this.extractor = extractor;
        this.clock = clock;
    }

    @Scheduled(cron = "0 10 9 * * *", zone = "Asia/Shanghai")
    public void extractPendingScheduled() {
        try {
            extractPending();
        } catch (Exception e) { // 调度保护：批处理绝不能炸调度线程
            log.error("政策抽取批异常", e);
        }
    }

    /**
     * 幂等可重入抽取入口（集成测试/运维也可直调）：循环取批直至窗口内无 PENDING、
     * LLM 失效、或护栏停批；批末按本轮 PARSE_FAILED 置换数告警（NFR-5）。同轮已处理
     * 条目记录在 {@code handled}——意外异常条目保持 PENDING 但本轮不重选（防同一轮内
     * 死循环），由后续批次在游标窗口内再试；短文本条目记入 {@code lengthSkipped} 同样
     * 同轮不重选，但不送 LLM、不落库、留 PENDING 由窗口自然过期。
     */
    public void extractPending() {
        int failedCount = extractInBatches();
        alertParseFailures(LocalDate.now(clock), failedCount);
    }

    /** 批处理主体（供 {@link #extractPending} 编排）：返回本轮 FAILED 置换条数。 */
    private int extractInBatches() {
        LocalDate today = LocalDate.now(clock);
        Set<Long> handled = new HashSet<>();
        Set<Long> lengthSkipped = new HashSet<>();
        int failedCount = 0;
        while (true) {
            if (tokenBudget.exhausted()) { // 晨间已超限的批次：不取数不调 LLM 快退
                stopForGuardrail(today);
                return failedCount;
            }
            List<PolicyRecord> batch = policyRepository
                    .findPendingForExtraction(today, EXTRACTION_LOOKBACK_DAYS,
                            props.getIntelligence().getExtractBatchSize())
                    .stream().filter(policy -> !handled.contains(policy.id())
                            && !lengthSkipped.contains(policy.id())).toList();
            if (batch.isEmpty()) { // 空批快退（无新发布/窗口内已清空）
                return failedCount;
            }
            for (PolicyRecord policy : batch) {
                if (tooShortForExtraction(policy)) { // 短文本过滤：不入 handled（未处置），仅同轮防重选
                    lengthSkipped.add(policy.id());
                    log.info("短文本跳过（policyRawId={}，title+正文合计长度 < {}）：不送 LLM、不落库，留 PENDING 由 {} 日游标窗口自然过期",
                            policy.id(), MIN_EXTRACT_INPUT_LENGTH, EXTRACTION_LOOKBACK_DAYS);
                    continue;
                }
                handled.add(policy.id());
                try {
                    PolicyExtractor.ExtractionOutcome outcome = extractor.extractOne(
                            chatPort, policy.title(), policy.contentText());
                    if (outcome.status() == PolicyExtractor.OutcomeStatus.LLM_UNAVAILABLE) {
                        log.warn("情报 LLM 通道不可用，本批跳过（剩余 {} 条留 PENDING 待后续批次续抽，{} 日游标窗口）",
                                batch.size() - batch.indexOf(policy), EXTRACTION_LOOKBACK_DAYS);
                        return failedCount;
                    }
                    PolicyExtractResult result = toResult(outcome);
                    policyRepository.upsertExtract(policy.id(), result);
                    if (result.status() == ExtractStatus.FAILED) { // PARSE_FAILED 置换计数（批末告警口径）
                        failedCount++;
                    }
                    if (!tokenBudget.tryAcquire(outcome.inputTokens())) { // 本条已落库，剩余停批
                        stopForGuardrail(today);
                        return failedCount;
                    }
                } catch (Exception e) { // 单条隔离：一条失败不拖垮其余
                    log.error("单条政策抽取异常（policyRawId={}），本条跳过待后续批次续抽", policy.id(), e);
                }
            }
        }
    }

    /** 短文本过滤：title+正文合计长度（null 计 0）低于阈值即跳过（政策正文都长，纯防御）。 */
    private static boolean tooShortForExtraction(PolicyRecord policy) {
        int titleLength = policy.title() == null ? 0 : policy.title().length();
        int contentLength = policy.contentText() == null ? 0 : policy.contentText().length();
        return titleLength + contentLength < MIN_EXTRACT_INPUT_LENGTH;
    }

    /** 批末解析失败告警（NFR-5）：本轮有 FAILED 置换且当日未告警过才发，文案含条数；尽力而为不重试。 */
    private void alertParseFailures(LocalDate today, int failedCount) {
        if (failedCount == 0 || today.equals(failedAlertedDay)) {
            return;
        }
        failedAlertedDay = today;
        boolean ok = alertNotifier.send("⚠️ 政策抽取出现解析失败", "red", List.of(
                "本轮抽取有 " + failedCount + " 条 LLM 输出解析失败（重试 1 次后仍败），已标 FAILED 终态",
                "FAILED 不自动重试，请抽查 intelligence_policy_event 的 FAILED 行评估提示词与模型稳定性"));
        if (!ok) {
            log.warn("政策抽取解析失败告警推送失败（不重试，同日如再触发按日去重不再重复告警）");
        }
    }

    /** 护栏超限停批：当日告警恰一次（AlertNotifier 尽力而为，失败不重试）。 */
    private void stopForGuardrail(LocalDate today) {
        long guardrail = props.getIntelligence().getDailyTokenGuardrail();
        log.warn("情报抽取当日 input token 超护栏（{}），当日停批，剩余条目留 PENDING 待后续批次续抽（{} 日游标窗口）",
                guardrail, EXTRACTION_LOOKBACK_DAYS);
        if (today.equals(alertedDay)) {
            return;
        }
        alertedDay = today;
        boolean ok = alertNotifier.send("⚠️ 情报抽取 token 护栏超限", "red", List.of(
                "当日累计 input tokens 已超过护栏 " + guardrail,
                "当日剩余批已停止，未抽取条目留 PENDING，" + EXTRACTION_LOOKBACK_DAYS + " 日游标窗口内后续批次续抽"));
        if (!ok) {
            log.warn("护栏超限告警推送失败（不重试，同日如再触发按日去重不再重复告警）");
        }
    }

    /** 抽取三态 → 落库结果：SUCCESS 带方向/力度/影响领域/摘要/置信度；FAILED 仅状态与留痕字段。 */
    private PolicyExtractResult toResult(PolicyExtractor.ExtractionOutcome outcome) {
        String model = props.getLlm().getModel();
        Instant extractedAt = Instant.now(clock);
        if (outcome.status() == PolicyExtractor.OutcomeStatus.SUCCESS) {
            PolicyExtractor.ExtractedFields fields = outcome.fields();
            return PolicyExtractResult.success(fields.direction(), fields.strength(),
                    fields.affectedAreas(), fields.summary(), fields.confidence(), model, extractedAt);
        }
        return new PolicyExtractResult(null, null, null, null, null,
                ExtractStatus.FAILED, model, extractedAt);
    }
}

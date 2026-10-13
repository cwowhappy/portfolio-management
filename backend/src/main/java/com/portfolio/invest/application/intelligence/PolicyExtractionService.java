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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 政策 LLM 结构化抽取批（F11/F12，MS-22 Task 5）：每日 09:10 一批（Asia/Shanghai，采集任务
 * 08:40 落 raw 后；含周末——空批快退），游标窗口内（{@link #EXTRACTION_LOOKBACK_DAYS} 个自然日
 * 含当日，判定列 published_at）的 PENDING 政策按 {@code extract-batch-size} 循环取批逐条抽取
 * 方向/力度/影响领域/摘要/置信度。「批量→逐条→失败隔离」骨架与批末告警在
 * {@link ExtractionBatchService} 模板（2026-10-12 审查 B2 归一，与新闻/公告同模板），
 * 本类只覆盖政策域差异点。
 *
 * <p>isPolicy=false 兜底（F12「误收录须标注为低置信」）：LLM 判定非政策类（领导活动/会议
 * 新闻/转载/行政事务漏网）的条目由 {@link PolicyExtractor} 置换为哨兵字段<b>落库而非丢弃</b>
 * （低置信标注，P4 页面与 T6 简报可过滤）；政策事件长期保留（FK 无级联删除，无清理任务）。
 *
 * <p>短文本跳过：title+正文合计 &lt; {@link #MIN_EXTRACT_INPUT_LENGTH} 的条目直接跳过
 * （政策正文都长，此为防御——不送 LLM、不落库、留 PENDING 由游标窗口自然过期）。
 * 无批末推送（政策不入 F10 公告推送——政策事件进简报「宏观与政策」节由 T6 消费
 * {@link PolicyRepository#searchEvents}，与本批解耦）。
 */
@Service
public class PolicyExtractionService extends ExtractionBatchService<PolicyRecord> {

    private static final Logger log = LoggerFactory.getLogger(PolicyExtractionService.class);

    /** 短文本跳过阈值：title+正文合计低于此字符数不值得过 LLM（政策正文都长，纯防御）。 */
    static final int MIN_EXTRACT_INPUT_LENGTH = 50;

    private final PolicyRepository policyRepository;
    private final IntelligenceChatPort chatPort;
    private final PolicyExtractor extractor;

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
        super(tokenBudget, alertNotifier, props, clock);
        this.policyRepository = policyRepository;
        this.chatPort = chatPort;
        this.extractor = extractor;
    }

    @Scheduled(cron = "0 10 9 * * *", zone = "Asia/Shanghai")
    public void extractPendingScheduled() {
        try {
            extractPending();
        } catch (Exception e) { // 调度保护：批处理绝不能炸调度线程
            log.error("政策抽取批异常", e);
        }
    }

    @Override
    protected List<PolicyRecord> findPendingBatch(LocalDate today) {
        return policyRepository.findPendingForExtraction(today, EXTRACTION_LOOKBACK_DAYS,
                props.getIntelligence().getExtractBatchSize());
    }

    @Override
    protected Long itemId(PolicyRecord item) {
        return item.id();
    }

    @Override
    protected boolean isTooShortForExtraction(PolicyRecord policy) {
        return tooShortForExtraction(policy);
    }

    @Override
    protected void onLengthSkipped(Long id) {
        log.info("短文本跳过（policyRawId={}，title+正文合计长度 < {}）：不送 LLM、不落库，留 PENDING 由 {} 日游标窗口自然过期",
                id, MIN_EXTRACT_INPUT_LENGTH, EXTRACTION_LOOKBACK_DAYS);
    }

    @Override
    protected ItemStep extractAndPersist(PolicyRecord policy) {
        PolicyExtractor.ExtractionOutcome outcome = extractor.extractOne(
                chatPort, policy.title(), policy.contentText());
        if (outcome.status() == PolicyExtractor.OutcomeStatus.LLM_UNAVAILABLE) {
            return new ItemStep(outcome.inputTokens(), false, true, false);
        }
        PolicyExtractResult result = toResult(outcome);
        policyRepository.upsertExtract(policy.id(), result);
        return new ItemStep(outcome.inputTokens(), result.status() == ExtractStatus.FAILED, false, false);
    }

    @Override
    protected void onItemError(Long id, Exception e) {
        log.error("单条政策抽取异常（policyRawId={}），本条跳过待后续批次续抽", id, e);
    }

    @Override
    protected void sendParseFailureAlert(int failedCount) {
        boolean ok = alertNotifier.send("⚠️ 政策抽取出现解析失败", "red", List.of(
                "本轮抽取有 " + failedCount + " 条 LLM 输出解析失败（重试 1 次后仍败），已标 FAILED 终态",
                "FAILED 不自动重试，请抽查 intelligence_policy_event 的 FAILED 行评估提示词与模型稳定性"));
        if (!ok) {
            log.warn("政策抽取解析失败告警推送失败（不重试，同日如再触发按日去重不再重复告警）");
        }
    }

    /** 短文本过滤：title+正文合计长度（null 计 0）低于阈值即跳过（政策正文都长，纯防御）。 */
    private static boolean tooShortForExtraction(PolicyRecord policy) {
        int titleLength = policy.title() == null ? 0 : policy.title().length();
        int contentLength = policy.contentText() == null ? 0 : policy.contentText().length();
        return titleLength + contentLength < MIN_EXTRACT_INPUT_LENGTH;
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

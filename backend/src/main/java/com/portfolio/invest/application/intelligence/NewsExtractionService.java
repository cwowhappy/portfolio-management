package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.application.alert.AlertNotifier;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.ExtractStatus;
import com.portfolio.invest.domain.intelligence.NewsExtractResult;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import com.portfolio.invest.domain.intelligence.NewsRepository;
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
 * 新闻 LLM 结构化抽取批（D4/D6/D16）：每日 07:40/16:40 两批（含周末——空批快退），游标窗口
 * 内（{@link #EXTRACTION_LOOKBACK_DAYS} 个自然日含当日）的 PENDING 新闻按
 * {@code extract-batch-size} 循环取批逐条抽取，「批量→逐条→失败隔离」骨架
 * （Task 13 eval 与 P2 公告/P3 政策抽取复用）。
 *
 * <p>失败语义：单条解析失败（含重试 1 次）标 FAILED 终态；LLM 通道不可用（未配置/调用失败）
 * 整批静默跳过、条目留 PENDING 下批再试（绝不误杀）；单条意外异常隔离不阻塞批次。顶层
 * try/catch 吞异常护调度线程（照 PrincipleAlertService/ResearchFalsifierScanService 双层模式）。
 * 批末统计本轮 PARSE_FAILED 置换数，&gt;0 时经 AlertNotifier 告警（独立于护栏告警的按日去重，NFR-5）。
 *
 * <p>D7 长度过滤：title+summary 合计 &lt; {@link #MIN_EXTRACT_INPUT_LENGTH} 的条目直接跳过
 * （不送 LLM、不落库、留 PENDING 由 {@link #EXTRACTION_LOOKBACK_DAYS} 日游标窗口自然过期）。
 *
 * <p>成本护栏（D16）：每条抽取后向共享 {@link IntelligenceTokenBudget} 计入 inputTokens，
 * 超限当日停批（已完成条目照常落库、剩余留 PENDING 待后续批次续抽）+ AlertNotifier 每日告警一次。
 * 幂等可重入：PENDING→SUCCESS/FAILED 置换，同轮已处理条目（含异常跳过者）不重复抽取；
 * LLM 失效/护栏停批的积压条目在 {@link #EXTRACTION_LOOKBACK_DAYS} 个自然日（含当日）的
 * 游标窗口内由后续批次续抽——窗口有界，陈年条目不无限重试。
 */
@Service
public class NewsExtractionService {

    private static final Logger log = LoggerFactory.getLogger(NewsExtractionService.class);

    /** 抽取游标窗口（自然日含当日）：LLM 失效/护栏停批后的积压跨日续抽上界。 */
    static final int EXTRACTION_LOOKBACK_DAYS = 3;

    /** D7 长度过滤阈值：title+summary 合计低于此长度的条目不值得过 LLM，直接跳过。 */
    static final int MIN_EXTRACT_INPUT_LENGTH = 8;

    private final NewsRepository newsRepository;
    private final IntelligenceChatPort chatPort;
    private final IntelligenceTokenBudget tokenBudget;
    private final AlertNotifier alertNotifier;
    private final InvestProperties props;
    private final NewsExtractor extractor;
    private final Clock clock;

    /** 护栏告警去重：同一自然日最多告警一次（跨日随日期比较自动失效）。 */
    private LocalDate alertedDay;

    /** 解析失败告警去重（独立于护栏告警）：同一自然日最多告警一次。 */
    private LocalDate failedAlertedDay;

    @Autowired
    public NewsExtractionService(NewsRepository newsRepository, IntelligenceChatPort chatPort,
                                 IntelligenceTokenBudget tokenBudget, AlertNotifier alertNotifier,
                                 InvestProperties props) {
        this(newsRepository, chatPort, tokenBudget, alertNotifier, props,
                new NewsExtractor(), Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    /** 测试构造器：注入抽取器与时钟。 */
    NewsExtractionService(NewsRepository newsRepository, IntelligenceChatPort chatPort,
                          IntelligenceTokenBudget tokenBudget, AlertNotifier alertNotifier,
                          InvestProperties props, NewsExtractor extractor, Clock clock) {
        this.newsRepository = newsRepository;
        this.chatPort = chatPort;
        this.tokenBudget = tokenBudget;
        this.alertNotifier = alertNotifier;
        this.props = props;
        this.extractor = extractor;
        this.clock = clock;
    }

    @Scheduled(cron = "0 40 7,16 * * *", zone = "Asia/Shanghai")
    public void extractPendingScheduled() {
        try {
            extractPending();
        } catch (Exception e) { // 调度保护：批处理绝不能炸调度线程
            log.error("新闻抽取批异常", e);
        }
    }

    /**
     * 幂等可重入抽取入口（集成测试/运维也可直调）：循环取批直至窗口内无 PENDING、
     * LLM 失效、或护栏停批；批末按本轮 PARSE_FAILED 置换数告警（NFR-5）。同轮已处理
     * 条目记录在 {@code handled}——意外异常条目保持 PENDING 但本轮不重选（防同一轮内
     * 死循环），由后续批次在游标窗口内再试；D7 短文本条目记入 {@code lengthSkipped}
     * 同样同轮不重选，但不送 LLM、不落库、留 PENDING 由窗口自然过期。
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
            if (tokenBudget.exhausted()) { // 晨间已超限的晚批：不取数不调 LLM 快退
                stopForGuardrail(today);
                return failedCount;
            }
            List<NewsRecord> batch = newsRepository
                    .findPendingForExtraction(today, EXTRACTION_LOOKBACK_DAYS,
                            props.getIntelligence().getExtractBatchSize())
                    .stream().filter(news -> !handled.contains(news.id())
                            && !lengthSkipped.contains(news.id())).toList();
            if (batch.isEmpty()) { // 空批快退（周末/节假日/窗口内已清空）
                return failedCount;
            }
            for (NewsRecord news : batch) {
                if (tooShortForExtraction(news)) { // D7 长度过滤：不入 handled（未处置），仅同轮防重选
                    lengthSkipped.add(news.id());
                    log.info("D7 短文本跳过（newsId={}，title+summary 合计长度 < {}）：不送 LLM、不落库，留 PENDING 由 {} 日游标窗口自然过期",
                            news.id(), MIN_EXTRACT_INPUT_LENGTH, EXTRACTION_LOOKBACK_DAYS);
                    continue;
                }
                handled.add(news.id());
                try {
                    NewsExtractor.ExtractionOutcome outcome =
                            extractor.extractOne(chatPort, news.title(), news.rawSummary());
                    if (outcome.status() == NewsExtractor.OutcomeStatus.LLM_UNAVAILABLE) {
                        log.warn("情报 LLM 通道不可用，本批跳过（剩余 {} 条留 PENDING 待后续批次续抽，{} 日游标窗口）",
                                batch.size() - batch.indexOf(news), EXTRACTION_LOOKBACK_DAYS);
                        return failedCount;
                    }
                    NewsExtractResult result = toResult(outcome);
                    newsRepository.upsertExtract(news.id(), result);
                    if (result.status() == ExtractStatus.FAILED) { // PARSE_FAILED 置换计数（批末告警口径）
                        failedCount++;
                    }
                    if (!tokenBudget.tryAcquire(outcome.inputTokens())) { // 本条已落库，剩余停批
                        stopForGuardrail(today);
                        return failedCount;
                    }
                } catch (Exception e) { // 单条隔离：一条失败不拖垮其余
                    log.error("单条新闻抽取异常（newsId={}），本条跳过待后续批次续抽", news.id(), e);
                }
            }
        }
    }

    /** D7 长度过滤：title+summary 合计长度（null 计 0）低于阈值即跳过。 */
    private static boolean tooShortForExtraction(NewsRecord news) {
        int titleLength = news.title() == null ? 0 : news.title().length();
        int summaryLength = news.rawSummary() == null ? 0 : news.rawSummary().length();
        return titleLength + summaryLength < MIN_EXTRACT_INPUT_LENGTH;
    }

    /** 批末解析失败告警（NFR-5）：本轮有 FAILED 置换且当日未告警过才发，文案含条数；尽力而为不重试。 */
    private void alertParseFailures(LocalDate today, int failedCount) {
        if (failedCount == 0 || today.equals(failedAlertedDay)) {
            return;
        }
        failedAlertedDay = today;
        boolean ok = alertNotifier.send("⚠️ 情报抽取出现解析失败", "red", List.of(
                "本轮抽取有 " + failedCount + " 条 LLM 输出解析失败（重试 1 次后仍败），已标 FAILED 终态",
                "FAILED 不自动重试，请抽查 intelligence_news_extract 的 FAILED 行评估提示词与模型稳定性"));
        if (!ok) {
            log.warn("解析失败告警推送失败（不重试，同日如再触发按日去重不再重复告警）");
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

    /** 抽取三态 → 落库结果：SUCCESS 带全部分析字段；FAILED 仅状态与留痕字段。 */
    private NewsExtractResult toResult(NewsExtractor.ExtractionOutcome outcome) {
        String model = props.getLlm().getModel();
        Instant extractedAt = Instant.now(clock);
        if (outcome.status() == NewsExtractor.OutcomeStatus.SUCCESS) {
            NewsExtractor.ExtractedFields fields = outcome.fields();
            return NewsExtractResult.success(fields.eventType(), fields.stockCodes(),
                    fields.industryCodes(), fields.summary(), fields.direction(), fields.keyNumbers(),
                    fields.importance(), model, extractedAt);
        }
        return new NewsExtractResult(null, null, null, null, null, null, null,
                ExtractStatus.FAILED, model, extractedAt);
    }
}

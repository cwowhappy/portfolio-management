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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 新闻 LLM 结构化抽取批（D4/D6/D16）：每日 07:40/16:40 两批（含周末——空批快退），游标窗口
 * 内（{@link #EXTRACTION_LOOKBACK_DAYS} 个自然日含当日）的 PENDING 新闻按
 * {@code extract-batch-size} 循环取批逐条抽取，「批量→逐条→失败隔离」骨架与批末告警在
 * {@link ExtractionBatchService} 模板（2026-10-12 审查 B2 归一），本类只覆盖新闻域差异点
 * （长度阈值/落库结果/告警话术）。政策/公告抽取批复用同一模板。
 *
 * <p>D7 长度过滤：title+summary 合计 &lt; {@link #MIN_EXTRACT_INPUT_LENGTH} 的条目直接跳过
 * （不送 LLM、不落库、留 PENDING 由 {@link #EXTRACTION_LOOKBACK_DAYS} 日游标窗口自然过期）。
 * 成本护栏（D16）：每条抽取后向共享 {@link IntelligenceTokenBudget} 计入 inputTokens，
 * 超限当日停批（模板承担告警）。
 */
@Service
public class NewsExtractionService extends ExtractionBatchService<NewsRecord> {

    private static final Logger log = LoggerFactory.getLogger(NewsExtractionService.class);

    /** D7 长度过滤阈值：title+summary 合计低于此长度的条目不值得过 LLM，直接跳过。 */
    static final int MIN_EXTRACT_INPUT_LENGTH = 8;

    private final NewsRepository newsRepository;
    private final IntelligenceChatPort chatPort;
    private final NewsExtractor extractor;

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
        super(tokenBudget, alertNotifier, props, clock);
        this.newsRepository = newsRepository;
        this.chatPort = chatPort;
        this.extractor = extractor;
    }

    @Scheduled(cron = "0 40 7,16 * * *", zone = "Asia/Shanghai")
    public void extractPendingScheduled() {
        try {
            extractPending();
        } catch (Exception e) { // 调度保护：批处理绝不能炸调度线程
            log.error("新闻抽取批异常", e);
        }
    }

    @Override
    protected List<NewsRecord> findPendingBatch(LocalDate today) {
        return newsRepository.findPendingForExtraction(today, EXTRACTION_LOOKBACK_DAYS,
                props.getIntelligence().getExtractBatchSize());
    }

    @Override
    protected Long itemId(NewsRecord item) {
        return item.id();
    }

    @Override
    protected boolean isTooShortForExtraction(NewsRecord news) {
        return tooShortForExtraction(news);
    }

    @Override
    protected void onLengthSkipped(Long id) {
        log.info("D7 短文本跳过（newsId={}，title+summary 合计长度 < {}）：不送 LLM、不落库，留 PENDING 由 {} 日游标窗口自然过期",
                id, MIN_EXTRACT_INPUT_LENGTH, EXTRACTION_LOOKBACK_DAYS);
    }

    @Override
    protected ItemStep extractAndPersist(NewsRecord news) {
        NewsExtractor.ExtractionOutcome outcome =
                extractor.extractOne(chatPort, news.title(), news.rawSummary());
        if (outcome.status() == NewsExtractor.OutcomeStatus.LLM_UNAVAILABLE) {
            return new ItemStep(outcome.inputTokens(), false, true, false);
        }
        NewsExtractResult result = toResult(outcome);
        newsRepository.upsertExtract(news.id(), result);
        return new ItemStep(outcome.inputTokens(), result.status() == ExtractStatus.FAILED, false, false);
    }

    @Override
    protected void onItemError(Long id, Exception e) {
        log.error("单条新闻抽取异常（newsId={}），本条跳过待后续批次续抽", id, e);
    }

    @Override
    protected void sendParseFailureAlert(int failedCount) {
        boolean ok = alertNotifier.send("⚠️ 新闻抽取出现解析失败", "red", List.of(
                "本轮抽取有 " + failedCount + " 条 LLM 输出解析失败（重试 1 次后仍败），已标 FAILED 终态",
                "FAILED 不自动重试，请抽查 intelligence_news_extract 的 FAILED 行评估提示词与模型稳定性"));
        if (!ok) {
            log.warn("解析失败告警推送失败（不重试，同日如再触发按日去重不再重复告警）");
        }
    }

    /** D7 长度过滤：title+summary 合计长度（null 计 0）低于阈值即跳过。 */
    private static boolean tooShortForExtraction(NewsRecord news) {
        int titleLength = news.title() == null ? 0 : news.title().length();
        int summaryLength = news.rawSummary() == null ? 0 : news.rawSummary().length();
        return titleLength + summaryLength < MIN_EXTRACT_INPUT_LENGTH;
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

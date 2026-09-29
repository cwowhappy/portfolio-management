package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.BriefSection;
import com.portfolio.invest.domain.intelligence.DailyBrief;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import com.portfolio.invest.domain.intelligence.BriefRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 盘前简报生成（D17/决策 #20~#23）：交易日 08:00 选取「昨夜今晨」（昨日 15:00 → 今日
 * 08:00）窗口内达阈情报，经 {@link BriefSelectionPolicy} 选取（全 MAJOR + 各节 WATCH
 * 补足 min~max）与 {@link BriefComposer} 拼装 5+1 节 markdown，归档
 * {@link BriefRepository}（Task 10 推送读档，生成/推送解耦）。
 *
 * <p>三态归档：GENERATED（正常，LLM 逐节导语缺席时为纯条目版——LLM empty/空串/异常一律
 * 降级不 FAILED，只有条目选取管线异常才 FAILED 留档 fail_reason）/ EMPTY_SIMPLE（窗口
 * 无达阈值情报，决策 #22：一句话 + 数据截止期别）/ FAILED。
 *
 * <p>幂等：findByDate 当日已有档（任一状态）即跳过——重跑不重复生成；FAILED 不自动
 * 重试（留档供查），人工清理行后可重生成。调度顶层吞异常护调度线程（照
 * PrincipleAlertService/NewsExtractionService 双层模式）。cron 自身限 MON-FRI，
 * {@link TradingCalendarPort} 再判节假日（D5 双判定）。
 */
@Service
public class BriefGenerationService {

    private static final Logger log = LoggerFactory.getLogger(BriefGenerationService.class);

    /** 市场时区（窗口折算与调度 zone 一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 选取窗口起点时刻：昨日 15:00（收盘后）——「昨夜今晨」。 */
    private static final LocalTime WINDOW_START_TIME = LocalTime.of(15, 0);

    /** fail_reason 截断长度（异常栈 toString 可能极长，留档取头部即可定位）。 */
    private static final int FAIL_REASON_MAX = 500;

    /** 节导语系统提示词：条目是结构化事实不重写，只写节导语。 */
    private static final String LEAD_SYSTEM_PROMPT = """
            你是 A 股晨报编辑。给定简报某一小节的入选条目（标题与摘要），为该小节写 2~3 句中文导语，
            概括本节核心信息与对 A 股的整体含义。约束：
            - 只依据给定条目，严禁编造或引入条目外的信息。
            - 只输出导语文本本身，不要小节标题、编号、Markdown 格式。""";

    private final NewsRepository newsRepository;
    private final BriefRepository briefRepository;
    private final IntelligenceChatPort chatPort;
    private final TradingCalendarPort tradingCalendar;
    private final InvestProperties props;
    private final Clock clock;

    @Autowired
    public BriefGenerationService(NewsRepository newsRepository, BriefRepository briefRepository,
                                  IntelligenceChatPort chatPort, TradingCalendarPort tradingCalendar,
                                  InvestProperties props) {
        this(newsRepository, briefRepository, chatPort, tradingCalendar, props,
                Clock.system(ZONE));
    }

    /** 测试构造器：注入时钟。 */
    BriefGenerationService(NewsRepository newsRepository, BriefRepository briefRepository,
                           IntelligenceChatPort chatPort, TradingCalendarPort tradingCalendar,
                           InvestProperties props, Clock clock) {
        this.newsRepository = newsRepository;
        this.briefRepository = briefRepository;
        this.chatPort = chatPort;
        this.tradingCalendar = tradingCalendar;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Shanghai")
    public void generateBriefScheduled() {
        try {
            generateBrief();
        } catch (Exception e) { // 调度保护：生成链路绝不能炸调度线程
            log.error("盘前简报调度异常", e);
        }
    }

    /**
     * 幂等生成入口（集成测试/运维也可直调）：非交易日跳过；当日已有档跳过；
     * 管线异常落 FAILED 留档不外抛。
     */
    public void generateBrief() {
        LocalDate today = LocalDate.now(clock);
        if (!tradingCalendar.isTradingDay(today)) {
            log.info("非交易日（{}），跳过盘前简报生成", today);
            return;
        }
        if (briefRepository.findByDate(today).isPresent()) {
            log.info("当日简报已有档（{}），幂等跳过（FAILED 档不自动重试，清理行后可重生成）", today);
            return;
        }
        try {
            generateFor(today);
        } catch (Exception e) {
            log.error("盘前简报生成异常，落 FAILED 留档（tradeDate={}）", today, e);
            briefRepository.save(DailyBrief.failed(today, truncate(e), model(), Instant.now(clock)));
        }
    }

    private void generateFor(LocalDate today) {
        Instant windowStart = today.minusDays(1).atTime(WINDOW_START_TIME).atZone(ZONE).toInstant();
        Instant now = Instant.now(clock);
        InvestProperties.Intelligence intelligence = props.getIntelligence();
        // 候选池：窗口内 ≥ watchAt 全体（MAJOR ∪ WATCH），档位切分交给 policy 纯函数
        List<NewsRecord> candidates =
                newsRepository.findMajorSince(windowStart, intelligence.getWatchThreshold());
        BriefSelectionPolicy.Selection selection = BriefSelectionPolicy.select(
                candidates, intelligence.getMajorThreshold(), intelligence.getWatchThreshold(),
                intelligence.getBriefMinItems(), intelligence.getBriefMaxItems());
        if (selection.empty()) {
            log.info("窗口内无达阈值情报（tradeDate={}，当日完成抽取 {} 条），落空简版",
                    today, newsRepository.countExtractedByDate(today));
            BriefComposer.Composed empty =
                    BriefComposer.empty(today, windowStart, now);
            briefRepository.save(DailyBrief.emptySimple(today, empty.contentMd(), model(), now));
            logPendingBacklog(today);
            return;
        }
        BriefComposer.Composed composed =
                BriefComposer.compose(today, selection, leadsFor(selection), windowStart, now);
        briefRepository.save(DailyBrief.generated(today, composed.contentMd(),
                composed.topStocks(), model(), now));
        log.info("盘前简报已归档（tradeDate={}，入选 {} 条）", today, selection.selected().size());
        logPendingBacklog(today);
    }

    /**
     * 归档后抽取积压可观测：游标窗口内仍 PENDING 的条数（无 limit 截断的真实剩余量）。
     * 08:00 生成时点在 07:40 抽取批之后——非零即 LLM 失效/护栏停批/D7 跳过留下的积压，
     * 待 16:40 批或后续批次在窗口内续抽；0 为常态不打日志。
     */
    private void logPendingBacklog(LocalDate today) {
        long pending = newsRepository.countPendingInWindow(today,
                NewsExtractionService.EXTRACTION_LOOKBACK_DAYS);
        if (pending > 0) {
            log.info("窗口内仍有 {} 条待抽取（{} 日游标窗口内 PENDING，待后续抽取批续抽）",
                    pending, NewsExtractionService.EXTRACTION_LOOKBACK_DAYS);
        }
    }

    /**
     * 逐节导语（仅非空节）：LLM empty/空串/单节异常一律降级为无导语（纯条目版仍
     * GENERATED）——导语是锦上添花，绝不因 LLM 故障拖垮归档。
     */
    private Map<BriefSection, String> leadsFor(BriefSelectionPolicy.Selection selection) {
        Map<BriefSection, String> leads = new EnumMap<>(BriefSection.class);
        for (BriefSection section : BriefSection.values()) {
            List<NewsRecord> items = selection.bySection().getOrDefault(section, List.of());
            if (items.isEmpty()) {
                continue;
            }
            try {
                Optional<IntelligenceChatPort.ChatOutcome> outcome = chatPort.complete(
                        LEAD_SYSTEM_PROMPT, leadUserPrompt(section, items));
                if (outcome.isPresent() && !outcome.get().text().isBlank()) {
                    leads.put(section, outcome.get().text().trim());
                }
            } catch (Exception e) { // 单节隔离：导语失败不影响其余节与归档
                log.warn("节导语生成失败，该节降级为无导语（section={}）", section, e);
            }
        }
        return leads;
    }

    private static String leadUserPrompt(BriefSection section, List<NewsRecord> items) {
        StringBuilder prompt = new StringBuilder("小节：").append(BriefComposer.sectionTitle(section));
        for (NewsRecord item : items) {
            prompt.append("\n- ").append(item.title());
            if (item.extractSummary() != null && !item.extractSummary().isBlank()) {
                prompt.append("——").append(item.extractSummary());
            }
        }
        return prompt.append("\n请输出该小节导语。").toString();
    }

    private String model() {
        return props.getLlm().getModel();
    }

    private static String truncate(Exception e) {
        String reason = e.toString();
        return reason.length() <= FAIL_REASON_MAX ? reason : reason.substring(0, FAIL_REASON_MAX);
    }
}

package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.BriefRepository;
import com.portfolio.invest.domain.intelligence.BriefStatus;
import com.portfolio.invest.domain.intelligence.DailyBrief;
import com.portfolio.invest.domain.intelligence.IntelligenceSubscription;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import com.portfolio.invest.domain.intelligence.PushLog;
import com.portfolio.invest.domain.intelligence.PushLogRepository;
import com.portfolio.invest.domain.intelligence.PushStatus;
import com.portfolio.invest.domain.intelligence.PushType;
import com.portfolio.invest.domain.intelligence.SubscriptionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 盘前简报推送（D11 P1 群推版 → D17 P4 个性化升级）：交易日 08:30（生成 08:00 之后）读
 * 当日档，逐 {@code findUserIdsWithPushEnabled} 用户分流（受众=总开关默认开口径 #27：
 * 无订阅行与显式开都在，仅显式关不在——零行部署全员可收，绑定未存订阅者同样可见；
 * 且账号须审批通过未停用，PENDING/停用用户不入受众）：
 *
 * <ul>
 *   <li><b>绑定用户单发</b>（{@code SubscriptionService.findOpenId} 命中 → sendToUser）：
 *       正文=contentMd 原文（GENERATED/EMPTY_SIMPLE 同形；超长截断 3000 照
 *       FeishuDialogueBridge 先例）+ 可选附节「⭐ 你关注的」——简报同选取窗口（昨日 15:00
 *       起，复用 {@link BriefGenerationService#WINDOW_START_TIME}）importance≥
 *       majorThreshold 的抽取条目，按该用户订阅 stocks∪industries 过滤取前 10 条，
 *       无命中不附节；附节池查询失败降级不附节（增值信息不挡主正文）。</li>
 *   <li><b>群兜底分流</b>：群版发送条件=「存在 pushEnabled 且未绑定的用户」（防这些人
 *       失联）；全部绑定不发群（避免双发）。逐用户处理异常也按未绑定归入群兜底。
 *       push_log target 分别记 openId（带 userId）/chatId（无归属）。</li>
 *   <li>FAILED 档：发一行「今日简报生成失败」提示卡（红色，供运维感知，不重发内容、
 *       不附节），单发/群同形；无档（生成任务漏跑）：不发不补陈旧。</li>
 * </ul>
 *
 * <p>逐用户 try/catch 隔离（一人失败不挡其余）；未配置（appId/appSecret/chatId 缺失）
 * 整体跳过不留 FAIL 痕（配置缺失是部署状态非推送失败，照 PrincipleAlertService 先例）；
 * 推送失败留痕 FAIL。cron 自身限 MON-FRI，{@link TradingCalendarPort} 再判节假日
 * （D5 双判定）。调度顶层吞异常护调度线程。
 */
@Service
public class BriefPushService {

    private static final Logger log = LoggerFactory.getLogger(BriefPushService.class);

    /** 市场时区（与调度 zone 一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 卡片正文截断上限（照 FeishuDialogueBridge 先例，飞书卡片单元素超长有渲染风险）。 */
    static final int MAX_CARD_CHARS = 3000;

    /** 个性化附节标题（D17：用户订阅命中的重大情报条目）。 */
    static final String FOCUS_SECTION_HEADER = "⭐ 你关注的";

    /** 附节命中条目上限（前 10 条——附节是增量提示不是第二份简报）。 */
    static final int FOCUS_MAX_ITEMS = 10;

    private static final String TRUNCATION_SUFFIX = "…（已截断）";
    private static final String REF_TABLE = "intelligence_daily_brief";

    private final BriefRepository briefRepository;
    private final IntelligencePushPort pushPort;
    private final PushLogRepository pushLogRepository;
    private final TradingCalendarPort tradingCalendar;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;
    private final NewsRepository newsRepository;
    private final InvestProperties props;
    private final Clock clock;

    @Autowired
    public BriefPushService(BriefRepository briefRepository, IntelligencePushPort pushPort,
                            PushLogRepository pushLogRepository, TradingCalendarPort tradingCalendar,
                            SubscriptionRepository subscriptionRepository,
                            SubscriptionService subscriptionService, NewsRepository newsRepository,
                            InvestProperties props) {
        this(briefRepository, pushPort, pushLogRepository, tradingCalendar, subscriptionRepository,
                subscriptionService, newsRepository, props, Clock.system(ZONE));
    }

    /** 测试构造器：注入时钟。 */
    BriefPushService(BriefRepository briefRepository, IntelligencePushPort pushPort,
                     PushLogRepository pushLogRepository, TradingCalendarPort tradingCalendar,
                     SubscriptionRepository subscriptionRepository,
                     SubscriptionService subscriptionService, NewsRepository newsRepository,
                     InvestProperties props, Clock clock) {
        this.briefRepository = briefRepository;
        this.pushPort = pushPort;
        this.pushLogRepository = pushLogRepository;
        this.tradingCalendar = tradingCalendar;
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionService = subscriptionService;
        this.newsRepository = newsRepository;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Shanghai")
    public void pushBriefScheduled() {
        try {
            pushBrief();
        } catch (Exception e) { // 调度保护：推送链路绝不能炸调度线程
            log.error("盘前简报推送调度异常", e);
        }
    }

    /**
     * 推送入口（集成测试/运维也可直调）：非交易日/未配置/无档跳过；其余逐 pushEnabled
     * 用户分流单发，存在未绑定（含处理异常）用户时群兜底，全程 push_log 留痕。
     */
    public void pushBrief() {
        LocalDate today = LocalDate.now(clock);
        if (!tradingCalendar.isTradingDay(today)) {
            log.info("非交易日（{}），跳过盘前简报推送", today);
            return;
        }
        InvestProperties.Im im = props.getIm();
        if (im.getAppId() == null || im.getAppId().isBlank() || im.getAppSecret() == null
                || im.getAppSecret().isBlank() || im.getChatId() == null || im.getChatId().isBlank()) {
            log.info("盘前简报推送未启用（飞书 im 配置缺失），跳过（{}）", today);
            return;
        }
        Optional<DailyBrief> found = briefRepository.findByDate(today);
        if (found.isEmpty()) {
            log.warn("当日无简报档（{}，生成任务漏跑？），不发不补陈旧", today);
            return;
        }
        DailyBrief brief = found.get();
        List<Long> userIds = subscriptionRepository.findUserIdsWithPushEnabled();
        if (userIds.isEmpty()) {
            // 受众口径=总开关默认开（#27）：空受众只剩全员显式关闭一态——留一行可观测，
            // 零行部署（默认开）不会走到这里
            log.info("盘前简报无推送受众（全员显式关闭推送开关），跳过（tradeDate={}，status={}）",
                    today, brief.status());
            return;
        }
        boolean failed = brief.status() == BriefStatus.FAILED;
        // 附节候选池一次取全（窗口/阈值=简报选取口径），逐用户仅在内存过滤
        List<NewsRecord> majorPool = failed ? List.of() : loadMajorPool(today);
        List<Long> unbound = new ArrayList<>();
        for (Long userId : userIds) {
            try {
                Optional<String> openId = subscriptionService.findOpenId(userId);
                if (openId.isEmpty()) {
                    unbound.add(userId);
                    continue;
                }
                sendToUser(userId, openId.get(), brief, today, majorPool);
            } catch (Exception e) { // 逐用户隔离：一人失败不挡其余；绑定态未知按未兜底处理防失联
                log.warn("盘前简报单用户处理失败，归入群兜底（userId={}）：{}", userId, e.getMessage());
                unbound.add(userId);
            }
        }
        if (!unbound.isEmpty()) {
            sendToGroup(brief, today, im.getChatId(), unbound.size());
        }
    }

    /** 单发：正文=contentMd（+非失败档的「⭐ 你关注的」附节），留痕 target=openId 带 userId。 */
    private void sendToUser(Long userId, String openId, DailyBrief brief, LocalDate today,
                            List<NewsRecord> majorPool) {
        boolean failed = brief.status() == BriefStatus.FAILED;
        List<String> body = failed
                ? List.of(failureLine(brief))
                : personalizedBody(brief, userId, majorPool);
        boolean ok = pushPort.sendToUser(openId, title(today), failed ? "red" : "blue", body);
        pushLogRepository.save(new PushLog(null, userId, PushType.BRIEF, openId,
                REF_TABLE, brief.id(), ok ? PushStatus.OK : PushStatus.FAIL,
                ok ? null : "飞书单发返回失败", Instant.now(clock)));
        if (ok) {
            log.debug("盘前简报已单发（userId={}，tradeDate={}，status={}）", userId, today, brief.status());
        } else {
            log.warn("盘前简报单发失败（userId={}，tradeDate={}）", userId, today);
        }
    }

    /** 群统一版（P1 行为保留）：留痕 target=chatId 无归属。 */
    private void sendToGroup(DailyBrief brief, LocalDate today, String chatId, int unboundCount) {
        boolean failed = brief.status() == BriefStatus.FAILED;
        boolean ok = failed
                ? pushPort.sendToGroup(title(today), "red", List.of(failureLine(brief)))
                : pushPort.sendToGroup(title(today), "blue", List.of(truncate(brief.contentMd())));
        pushLogRepository.save(new PushLog(null, null, PushType.BRIEF, chatId,
                REF_TABLE, brief.id(), ok ? PushStatus.OK : PushStatus.FAIL,
                ok ? null : "飞书群推返回失败", Instant.now(clock)));
        if (ok) {
            log.info("盘前简报已群推兜底（tradeDate={}，status={}，未绑定推送用户 {} 人）",
                    today, brief.status(), unboundCount);
        } else {
            log.warn("盘前简报群推失败（tradeDate={}，status={}）", today, brief.status());
        }
    }

    /** 个性化正文：简报 md + 无命中不加的「⭐ 你关注的」附节。 */
    private List<String> personalizedBody(DailyBrief brief, Long userId, List<NewsRecord> majorPool) {
        List<String> body = new ArrayList<>();
        body.add(truncate(brief.contentMd()));
        List<String> focusLines = focusLines(majorPool, subscriptionOf(userId));
        if (!focusLines.isEmpty()) {
            body.add(FOCUS_SECTION_HEADER);
            body.addAll(focusLines);
        }
        return body;
    }

    /** 附节行：候选池按订阅 stocks∪industries 过滤取前 10，逐行「- 标题——摘要」。 */
    private static List<String> focusLines(List<NewsRecord> majorPool, IntelligenceSubscription subscription) {
        Set<String> stocks = subscription.stocks().stream()
                .map(stock -> stock.stockCode()).collect(Collectors.toSet());
        Set<String> industries = subscription.industries();
        if (stocks.isEmpty() && industries.isEmpty()) {
            return List.of();
        }
        return majorPool.stream()
                .filter(item -> intersects(item.stockCodes(), stocks)
                        || intersects(item.industryCodes(), industries))
                .limit(FOCUS_MAX_ITEMS)
                .map(item -> {
                    String line = item.extractSummary() == null || item.extractSummary().isBlank()
                            ? "- " + item.title() : "- " + item.title() + "——" + item.extractSummary();
                    return truncate(line);
                })
                .toList();
    }

    /** 当前订阅聚合：无行物化缺省（pushEnabled=true 空集——附节自然为空）。 */
    private IntelligenceSubscription subscriptionOf(Long userId) {
        return subscriptionRepository.findByUserId(userId)
                .orElseGet(() -> IntelligenceSubscription.defaults(userId));
    }

    /** 附节候选池：简报同窗口（昨日 15:00 起）≥ majorThreshold 的 SUCCESS 抽取条目；失败降级空池。 */
    private List<NewsRecord> loadMajorPool(LocalDate today) {
        try {
            Instant windowStart = today.minusDays(1)
                    .atTime(BriefGenerationService.WINDOW_START_TIME)
                    .atZone(ZONE).toInstant();
            return newsRepository.findMajorSince(windowStart,
                    props.getIntelligence().getMajorThreshold());
        } catch (Exception e) { // 附节是增值信息：查询失败降级不附节，不挡主正文推送
            log.warn("「你关注的」附节候选查询失败，降级不附节（tradeDate={}）：{}", today, e.getMessage());
            return List.of();
        }
    }

    private static boolean intersects(List<String> codes, Set<String> subscribed) {
        for (String code : codes) {
            if (subscribed.contains(code)) {
                return true;
            }
        }
        return false;
    }

    private static String title(LocalDate date) {
        return "【盘前简报】" + date;
    }

    /** FAILED 档一行提示（含截断后的失败原因），供运维感知。 */
    private static String failureLine(DailyBrief brief) {
        String reason = brief.failReason() == null || brief.failReason().isBlank()
                ? "未知原因" : brief.failReason();
        return truncate("今日简报生成失败：" + reason);
    }

    static String truncate(String text) {
        return text.length() <= MAX_CARD_CHARS ? text
                : text.substring(0, MAX_CARD_CHARS) + TRUNCATION_SUFFIX;
    }
}

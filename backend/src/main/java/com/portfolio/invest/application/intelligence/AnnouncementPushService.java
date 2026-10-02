package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.application.research.IntelligenceSubscriptionHook;
import com.portfolio.invest.application.research.IntelligenceSubscriptionHook.IntelligenceTarget;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.AnnouncementMetrics;
import com.portfolio.invest.domain.intelligence.AnnouncementRecord;
import com.portfolio.invest.domain.intelligence.AnnouncementRepository;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import com.portfolio.invest.domain.intelligence.FeishuBindingRepository;
import com.portfolio.invest.domain.intelligence.IntelligenceSubscription;
import com.portfolio.invest.domain.intelligence.PushLog;
import com.portfolio.invest.domain.intelligence.PushLogRepository;
import com.portfolio.invest.domain.intelligence.PushStatus;
import com.portfolio.invest.domain.intelligence.PushType;
import com.portfolio.invest.domain.intelligence.SubscriptionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 公告定向推送（D12/D15，决策 #26/#8，实现 {@link AnnouncementPushTrigger}——由
 * {@code AnnouncementExtractionService} 抽取批批末以批起点触发，无独立 cron）：
 *
 * <ul>
 *   <li><b>命中集 = 手动订阅 ∪ 持仓 hook</b>（决策 #26 union）：订阅路走
 *       {@link SubscriptionRepository#findAllWithStock}（仓库已滤 push_enabled，开关关用户
 *       两路都不命中）；hook 路 {@link IntelligenceSubscriptionHook#activePositionTargets}
 *       一次取全量按 stockCode 分组（intelligence_alert_enabled 已在实现侧滤）。</li>
 *   <li><b>按 (announcement, user) 聚合一次推送</b>：同一用户经多路/多项目命中同一公告
 *       只发一条、留一行痕；多条公告逐条推送不跨公告聚合（重大公告逐条触达，决策 #8
 *       单条长文精神）。</li>
 *   <li><b>留痕三态</b>：OK / FAIL / SKIPPED_NO_BINDING（未绑定飞书——跳过单发仍留痕，
 *       P4 绑定就位后自然生效）。幂等：push_log 已有该 (公告, 用户) 的 OK/SKIPPED 行则
 *       跳过（FAIL 允许下批重推）。</li>
 *   <li><b>尽力而为</b>：顶层/单公告/单用户三层隔离，任一失败不挡其余；im 未配置
 *       （appId/appSecret 缺失）整体跳过不留痕（部署状态非推送失败，照
 *       {@code BriefPushService} 先例）。</li>
 * </ul>
 *
 * <p>卡片正文：类型行（annTypes 中文名，空则源站栏目，再空「其他」）+ 要点行
 * （metrics 非空取关键数字行；全空降级为全标题行——宽栏目/杂项公告形态）+ 原文链接行
 * （pdf_url 有才出）。
 */
@Service
public class AnnouncementPushService implements AnnouncementPushTrigger {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementPushService.class);

    /** 市场时区（与抽取批调度 zone 一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String REF_TABLE = "intelligence_announcement";
    private static final String CARD_TEMPLATE = "blue";
    private static final int TITLE_MAX_CHARS = 64;

    /**
     * SKIPPED_NO_BINDING 行的 target 占位：列 NOT NULL（V3 DDL）而未绑定用户无 open_id
     * 可写，以哨兵落列——幂等查重只看 (push_type, ref, user, status)，不受影响。
     */
    static final String NO_BINDING_TARGET = "UNBOUND";

    private final AnnouncementRepository announcementRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final IntelligenceSubscriptionHook subscriptionHook;
    private final FeishuBindingRepository bindingRepository;
    private final IntelligencePushPort pushPort;
    private final PushLogRepository pushLogRepository;
    private final InvestProperties props;
    private final Clock clock;

    @Autowired
    public AnnouncementPushService(AnnouncementRepository announcementRepository,
                                   SubscriptionRepository subscriptionRepository,
                                   IntelligenceSubscriptionHook subscriptionHook,
                                   FeishuBindingRepository bindingRepository,
                                   IntelligencePushPort pushPort,
                                   PushLogRepository pushLogRepository,
                                   InvestProperties props) {
        this(announcementRepository, subscriptionRepository, subscriptionHook, bindingRepository,
                pushPort, pushLogRepository, props, Clock.system(ZONE));
    }

    /** 测试构造器：注入时钟。 */
    AnnouncementPushService(AnnouncementRepository announcementRepository,
                            SubscriptionRepository subscriptionRepository,
                            IntelligenceSubscriptionHook subscriptionHook,
                            FeishuBindingRepository bindingRepository,
                            IntelligencePushPort pushPort,
                            PushLogRepository pushLogRepository,
                            InvestProperties props, Clock clock) {
        this.announcementRepository = announcementRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionHook = subscriptionHook;
        this.bindingRepository = bindingRepository;
        this.pushPort = pushPort;
        this.pushLogRepository = pushLogRepository;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public void pushExtracted(Instant since) {
        try {
            doPush(since);
        } catch (Exception e) { // 顶层兜底：推送绝不能拖垮抽取批（触发侧另有防御包裹，双保险）
            log.error("公告定向推送批异常（since={}）", since, e);
        }
    }

    private void doPush(Instant since) {
        InvestProperties.Im im = props.getIm();
        if (im.getAppId() == null || im.getAppId().isBlank() || im.getAppSecret() == null
                || im.getAppSecret().isBlank()) {
            log.info("公告定向推送未启用（飞书 im 配置缺失），跳过（since={}）", since);
            return;
        }
        List<AnnouncementRecord> announcements = announcementRepository.findExtractedMajorSince(since);
        if (announcements.isEmpty()) {
            log.debug("推送窗口无完成抽取的 major 公告（since={}）", since);
            return;
        }
        Map<String, List<IntelligenceTarget>> hookByStock = subscriptionHook.activePositionTargets()
                .stream()
                .collect(Collectors.groupingBy(IntelligenceTarget::stockCode));
        log.info("公告定向推送开始（since={}，公告 {} 条，持仓挂接标的 {} 个）",
                since, announcements.size(), hookByStock.size());
        for (AnnouncementRecord announcement : announcements) {
            try {
                pushOneAnnouncement(announcement, hookByStock);
            } catch (Exception e) { // 单公告隔离：一条失败不挡其余
                log.warn("公告推送单条失败（announcementId={}）：{}", announcement.id(), e.getMessage());
            }
        }
    }

    /** 单公告：构建 union 命中集（订阅 ∪ hook，按 userId 聚合去重）→ 逐用户推送留痕。 */
    private void pushOneAnnouncement(AnnouncementRecord announcement,
                                     Map<String, List<IntelligenceTarget>> hookByStock) {
        Map<Long, UserHit> hits = new TreeMap<>();
        for (IntelligenceSubscription subscription
                : subscriptionRepository.findAllWithStock(announcement.stockCode())) {
            hits.computeIfAbsent(subscription.userId(), UserHit::new);
        }
        for (IntelligenceTarget target : hookByStock.getOrDefault(announcement.stockCode(), List.of())) {
            hits.computeIfAbsent(target.userId(), UserHit::new).projectIds().add(target.projectId());
        }
        if (hits.isEmpty()) {
            return;
        }
        String title = cardTitle(announcement);
        List<String> bodyLines = bodyLines(announcement);
        for (UserHit hit : hits.values()) {
            try {
                pushToUser(hit, announcement, title, bodyLines);
            } catch (Exception e) { // 单用户隔离：一人失败不挡其余
                log.warn("公告推送单用户失败（announcementId={}，userId={}）：{}",
                        announcement.id(), hit.userId(), e.getMessage());
            }
        }
    }

    private void pushToUser(UserHit hit, AnnouncementRecord announcement, String title,
                            List<String> bodyLines) {
        if (pushLogRepository.existsAnnouncementPush(announcement.id(), hit.userId())) {
            log.debug("公告推送幂等跳过（announcementId={}，userId={}）",
                    announcement.id(), hit.userId());
            return;
        }
        Optional<String> openId = bindingRepository.findOpenIdByUserId(hit.userId());
        if (openId.isEmpty()) {
            pushLogRepository.save(new PushLog(null, hit.userId(), PushType.ANNOUNCEMENT,
                    NO_BINDING_TARGET, REF_TABLE, announcement.id(),
                    PushStatus.SKIPPED_NO_BINDING, null, Instant.now(clock)));
            hit.projectIds().forEach(projectId ->
                    writeProjectJournalHit(hit.userId(), projectId, announcement, bodyLines));
            return;
        }
        boolean ok = pushPort.sendToUser(openId.get(), title, CARD_TEMPLATE, bodyLines);
        pushLogRepository.save(new PushLog(null, hit.userId(), PushType.ANNOUNCEMENT, openId.get(),
                REF_TABLE, announcement.id(), ok ? PushStatus.OK : PushStatus.FAIL,
                ok ? null : "飞书单发返回失败", Instant.now(clock)));
        hit.projectIds().forEach(projectId ->
                writeProjectJournalHit(hit.userId(), projectId, announcement, bodyLines));
    }

    /**
     * journal 留痕方法位（D14，P4 回接收口激活）：推送命中持仓项目（IntelligenceTarget
     * 带 projectId）时，按「【情报】」前缀复用 RESEARCH_EVENT 写项目时间线（公告标题/类型/
     * 要点/链接）。本册不写 journal 表——空实现预留，激活时经 JournalEntryRepository
     * 直写（照 application/research/ResearchApplicationService 私有 writeEvent 模式，
     * JournalEntry.create 带 projectId 重载）。推送与留痕失败均不影响本方法（尽力而为）。
     */
    private void writeProjectJournalHit(Long userId, Long projectId, AnnouncementRecord announcement,
                                        List<String> bodyLines) {
        // TODO P4（MS-23 回接收口）：JournalEntry.create(projectId 重载) + RESEARCH_EVENT 落库，
        //   title=「【情报】」+ 公告标题，content=String.join("\n", bodyLines)。
    }

    // ── 卡片文案组装（纯函数，四类行形态见 Task 5 报告 §T7 消费口径）──────────

    private static String cardTitle(AnnouncementRecord announcement) {
        String stock = announcement.stockName() == null || announcement.stockName().isBlank()
                ? announcement.stockCode() : announcement.stockName();
        return truncate("【公告提醒】" + stock + "：" + announcement.title());
    }

    /** 类型行 + 要点行（metrics 全空降级全标题行）+ 链接行（pdf_url 有才出）。 */
    private static List<String> bodyLines(AnnouncementRecord announcement) {
        List<String> lines = new ArrayList<>();
        lines.add("类型：" + typeLabel(announcement));
        List<String> metricsLines = metricsLines(announcement.metrics());
        if (metricsLines.isEmpty()) {
            lines.add("标题：" + announcement.title());
        } else {
            lines.addAll(metricsLines);
        }
        if (announcement.pdfUrl() != null && !announcement.pdfUrl().isBlank()) {
            lines.add("原文：" + announcement.pdfUrl());
        }
        return lines;
    }

    /** annTypes 中文名（剔 OTHER）；空则源站栏目；再空「其他」。 */
    private static String typeLabel(AnnouncementRecord announcement) {
        String joined = announcement.annTypes().stream()
                .filter(type -> type != AnnouncementType.OTHER)
                .map(AnnouncementType::label)
                .collect(Collectors.joining("、"));
        if (!joined.isEmpty()) {
            return joined;
        }
        return announcement.annTypeSource() != null && !announcement.annTypeSource().isBlank()
                ? announcement.annTypeSource() : AnnouncementType.OTHER.label();
    }

    /** metrics 关键数字行（六字段非空才出；全空返回空列表 → 调用方降级标题行）。 */
    private static List<String> metricsLines(AnnouncementMetrics metrics) {
        if (metrics == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        if (metrics.revenueYi() != null) {
            lines.add("营业收入：" + metrics.revenueYi().toPlainString() + " 亿元");
        }
        if (metrics.netProfitYi() != null) {
            lines.add("归母净利润：" + metrics.netProfitYi().toPlainString() + " 亿元");
        }
        if (metrics.netProfitYoyPct() != null) {
            lines.add("净利润同比：" + signedPercent(metrics.netProfitYoyPct()));
        }
        if (metrics.deductedProfitYi() != null) {
            lines.add("扣非净利润：" + metrics.deductedProfitYi().toPlainString() + " 亿元");
        }
        if (metrics.grossMarginPct() != null) {
            lines.add("毛利率：" + signedPercent(metrics.grossMarginPct()));
        }
        if (metrics.dividendDesc() != null && !metrics.dividendDesc().isBlank()) {
            lines.add("分红：" + metrics.dividendDesc());
        }
        return lines;
    }

    private static String signedPercent(BigDecimal value) {
        return (value.signum() >= 0 ? "+" : "") + value.toPlainString() + "%";
    }

    private static String truncate(String text) {
        return text.length() <= TITLE_MAX_CHARS ? text
                : text.substring(0, TITLE_MAX_CHARS) + "…";
    }

    /** 单公告内按 userId 聚合的命中（订阅/hook 多路去重；projectIds 供 journal 留痕锚点）。 */
    private static final class UserHit {

        private final Long userId;
        private final TreeSet<Long> projectIds = new TreeSet<>();

        private UserHit(Long userId) {
            this.userId = userId;
        }

        private Long userId() {
            return userId;
        }

        private TreeSet<Long> projectIds() {
            return projectIds;
        }
    }
}

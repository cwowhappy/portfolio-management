package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.BriefRepository;
import com.portfolio.invest.domain.intelligence.BriefStatus;
import com.portfolio.invest.domain.intelligence.DailyBrief;
import com.portfolio.invest.domain.intelligence.PushLog;
import com.portfolio.invest.domain.intelligence.PushLogRepository;
import com.portfolio.invest.domain.intelligence.PushStatus;
import com.portfolio.invest.domain.intelligence.PushType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 盘前简报推送（D11，P1 群推版）：交易日 08:30（生成 08:00 之后）读当日档群推配置群，
 * 推送留痕 intelligence_push_log（NFR-5）。生成/推送解耦——本服务只消费
 * {@link BriefRepository} 已归档的三态：
 *
 * <ul>
 *   <li>GENERATED / EMPTY_SIMPLE：正文=contentMd 原文（lark_md 直吃 markdown，超长截断
 *       3000 字符照 FeishuDialogueBridge 先例）照发；</li>
 *   <li>FAILED：发一行「今日简报生成失败」提示卡（红色，供运维感知，不重发内容）；</li>
 *   <li>无档（生成任务漏跑）：不发不补陈旧——缺档告警由 FAILED 留档与人工巡检兜。</li>
 * </ul>
 *
 * <p>未配置（appId/appSecret/chatId 缺失）整体跳过不留 FAIL 痕（配置缺失是部署状态
 * 非推送失败，照 PrincipleAlertService 先例）；推送失败留痕 FAIL。cron 自身限 MON-FRI，
 * {@link TradingCalendarPort} 再判节假日（D5 双判定，照 BriefGenerationService）。
 * 调度顶层吞异常护调度线程。P4 订阅单发（sendToUser）接绑定关系后启用。
 */
@Service
public class BriefPushService {

    private static final Logger log = LoggerFactory.getLogger(BriefPushService.class);

    /** 市场时区（与调度 zone 一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 卡片正文截断上限（照 FeishuDialogueBridge 先例，飞书卡片单元素超长有渲染风险）。 */
    static final int MAX_CARD_CHARS = 3000;

    private static final String TRUNCATION_SUFFIX = "…（已截断）";
    private static final String REF_TABLE = "intelligence_daily_brief";

    private final BriefRepository briefRepository;
    private final IntelligencePushPort pushPort;
    private final PushLogRepository pushLogRepository;
    private final TradingCalendarPort tradingCalendar;
    private final InvestProperties props;
    private final Clock clock;

    @Autowired
    public BriefPushService(BriefRepository briefRepository, IntelligencePushPort pushPort,
                            PushLogRepository pushLogRepository, TradingCalendarPort tradingCalendar,
                            InvestProperties props) {
        this(briefRepository, pushPort, pushLogRepository, tradingCalendar, props,
                Clock.system(ZONE));
    }

    /** 测试构造器：注入时钟。 */
    BriefPushService(BriefRepository briefRepository, IntelligencePushPort pushPort,
                     PushLogRepository pushLogRepository, TradingCalendarPort tradingCalendar,
                     InvestProperties props, Clock clock) {
        this.briefRepository = briefRepository;
        this.pushPort = pushPort;
        this.pushLogRepository = pushLogRepository;
        this.tradingCalendar = tradingCalendar;
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

    /** 推送入口（集成测试/运维也可直调）：非交易日/未配置/无档跳过，其余推 + 留痕。 */
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
        boolean failed = brief.status() == BriefStatus.FAILED;
        boolean ok = failed
                ? pushPort.sendToGroup(title(today), "red", List.of(failureLine(brief)))
                : pushPort.sendToGroup(title(today), "blue", List.of(truncate(brief.contentMd())));
        pushLogRepository.save(new PushLog(null, null, PushType.BRIEF, im.getChatId(),
                REF_TABLE, brief.id(), ok ? PushStatus.OK : PushStatus.FAIL,
                ok ? null : "飞书群推返回失败", Instant.now(clock)));
        if (ok) {
            log.info("盘前简报已群推（tradeDate={}，status={}）", today, brief.status());
        } else {
            log.warn("盘前简报群推失败（tradeDate={}，status={}）", today, brief.status());
        }
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

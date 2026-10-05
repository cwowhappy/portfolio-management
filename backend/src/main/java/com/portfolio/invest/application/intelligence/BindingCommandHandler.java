package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.application.im.ImCommandRouter;
import com.portfolio.invest.application.im.ImInboundMessage;
import com.portfolio.invest.domain.intelligence.IntelligenceErrorCode;
import com.portfolio.invest.domain.intelligence.IntelligenceException;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 飞书绑定命令前置路由（P4 Task 5，D8 绑定闭环）：入站文本 trim 后命中
 * {@code ^\d{6}$} 或 {@code ^绑定\s?\d{6}$} 即在对话桥之前核销绑定码
 * （SubscriptionService.bindByCode），按结果回固定话术——不命中返回 empty 照旧
 * 透传 LLM 对话桥。话术面向飞书用户直读（不经 LLM 改写）：错误码→固定文案，
 * 成功提示推送价值。
 *
 * <p>P2-B10：绑定命令按 open_id 冷却——同 open_id 两条命令至少间隔 60s（首次尝试即占位，
 * 无论核销成败：失败重试正是穷举 6 位码的形态），冷却命中回固定提示且不调 bindByCode。
 */
@Component
public class BindingCommandHandler implements ImCommandRouter {

    /** 命令形态：裸 6 位码 / 「绑定」前缀（至多一个 \s 空白分隔），捕获组为码。 */
    static final Pattern BINDING_COMMAND = Pattern.compile("^(?:绑定\\s?)?(\\d{6})$");

    /** B10：同 open_id 两条绑定命令的最小间隔。 */
    static final long COOLDOWN_MILLIS = 60_000;

    private final SubscriptionService subscriptionService;
    private final Clock clock;
    /** B10：open_id → 最近一次命令尝试时刻（compute 保证 per-key 原子，照 LoginRateLimiter 先例）。 */
    private final ConcurrentMap<String, Long> lastBindAt = new ConcurrentHashMap<>();

    @Autowired
    public BindingCommandHandler(SubscriptionService subscriptionService) {
        this(subscriptionService, Clock.systemUTC());
    }

    /** 测试构造器：注入时钟（冷却断言确定性；A3 application 层禁直取系统时钟）。 */
    BindingCommandHandler(SubscriptionService subscriptionService, Clock clock) {
        this.subscriptionService = subscriptionService;
        this.clock = clock;
    }

    @Override
    public Optional<String> tryRoute(ImInboundMessage message) {
        String code = extractCode(message.text());
        if (code == null) {
            return Optional.empty(); // 非绑定命令：透传对话桥
        }
        if (blockedByCooldown(message.openId())) {
            return Optional.of("操作过于频繁，请稍后再试"); // 冷却命中：不调 bindByCode
        }
        try {
            subscriptionService.bindByCode(code, message.openId());
            return Optional.of("绑定成功：将为你推送个性化盘前简报与重大公告提醒");
        } catch (IntelligenceException e) {
            return Optional.of(replyFor(e.code()));
        }
    }

    /**
     * B10 冷却占位：首次尝试即记时，冷却期内命中不刷新时间戳（窗口自首次尝试起算），
     * 期满后下一次尝试重新占位。compute 保证同 open_id 并发命令的检查+占位原子。
     */
    private boolean blockedByCooldown(String openId) {
        long now = clock.millis();
        boolean[] blocked = {false};
        lastBindAt.compute(openId, (k, last) -> {
            if (last != null && now - last < COOLDOWN_MILLIS) {
                blocked[0] = true;
                return last;
            }
            return now;
        });
        return blocked[0];
    }

    /** 错误码→话术：OPEN_ID_TAKEN 单列，其余（BINDING_CODE_EXPIRED：不存在/过期/已用）归一文案。 */
    private static String replyFor(String errorCode) {
        return switch (errorCode) {
            case IntelligenceErrorCode.OPEN_ID_TAKEN -> "该飞书账号已绑定其他用户";
            default -> "绑定码无效或已过期，请在设置页重新生成";
        };
    }

    /** trim 后匹配命令形态则返回抽取的 6 位码，否则 null（text 为 null 的非文本消息不命中）。
     *  B10 起同时供 FeishuWsClient 非 owner 门做命令格式复判（与真实命令形态保持同一口径）。 */
    public static String extractCode(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = BINDING_COMMAND.matcher(text.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }
}

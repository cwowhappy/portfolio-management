package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.application.im.ImCommandRouter;
import com.portfolio.invest.application.im.ImInboundMessage;
import com.portfolio.invest.domain.intelligence.IntelligenceErrorCode;
import com.portfolio.invest.domain.intelligence.IntelligenceException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 飞书绑定命令前置路由（P4 Task 5，D8 绑定闭环）：入站文本 trim 后命中
 * {@code ^\d{6}$} 或 {@code ^绑定\s?\d{6}$} 即在对话桥之前核销绑定码
 * （SubscriptionService.bindByCode），按结果回固定话术——不命中返回 empty 照旧
 * 透传 LLM 对话桥。话术面向飞书用户直读（不经 LLM 改写）：错误码→固定文案，
 * 成功提示推送价值。
 */
@Component
public class BindingCommandHandler implements ImCommandRouter {

    /** 命令形态：裸 6 位码 / 「绑定」前缀（至多一个 \s 空白分隔），捕获组为码。 */
    static final Pattern BINDING_COMMAND = Pattern.compile("^(?:绑定\\s?)?(\\d{6})$");

    private final SubscriptionService subscriptionService;

    public BindingCommandHandler(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @Override
    public Optional<String> tryRoute(ImInboundMessage message) {
        String code = extractCode(message.text());
        if (code == null) {
            return Optional.empty(); // 非绑定命令：透传对话桥
        }
        try {
            subscriptionService.bindByCode(code, message.openId());
            return Optional.of("绑定成功：将为你推送个性化盘前简报与重大公告提醒");
        } catch (IntelligenceException e) {
            return Optional.of(replyFor(e.code()));
        }
    }

    /** 错误码→话术：OPEN_ID_TAKEN 单列，其余（BINDING_CODE_EXPIRED：不存在/过期/已用）归一文案。 */
    private static String replyFor(String errorCode) {
        return switch (errorCode) {
            case IntelligenceErrorCode.OPEN_ID_TAKEN -> "该飞书账号已绑定其他用户";
            default -> "绑定码无效或已过期，请在设置页重新生成";
        };
    }

    /** trim 后匹配命令形态则返回抽取的 6 位码，否则 null（text 为 null 的非文本消息不命中）。 */
    static String extractCode(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = BINDING_COMMAND.matcher(text.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }
}

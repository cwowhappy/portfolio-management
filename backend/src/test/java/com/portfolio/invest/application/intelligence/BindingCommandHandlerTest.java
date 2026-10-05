package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.portfolio.invest.application.im.ImInboundMessage;
import com.portfolio.invest.domain.intelligence.IntelligenceErrorCode;
import com.portfolio.invest.domain.intelligence.IntelligenceException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 绑定命令路由单测（P4 Task 5，D8 绑定闭环）：裸 6 位码 / 「绑定」前缀（至多一个空白）
 * 两形态命中并抽取码核销；非命令文本（含 null/空白包裹/非 6 位/双空格前缀）返回 empty
 * 透传对话桥且不触碰订阅服务；三失败态（不存在/过期/已用 → BINDING_CODE_EXPIRED）
 * 与 OPEN_ID_TAKEN 映射固定话术，成功映射推送价值话术。mock SubscriptionService 驱动。
 *
 * <p>B10：绑定命令按 open_id 60s 冷却——同 openId 第二条命中冷却提示且不重复核销、
 * 不同 openId 互不影响、时钟推进 61s 恢复放行（可拨动时钟注入，不等待真实墙钟）。
 */
class BindingCommandHandlerTest {

    private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    private final BindingCommandHandler handler = new BindingCommandHandler(subscriptionService);

    private static ImInboundMessage msg(String text) {
        return new ImInboundMessage("oc_1", "om_1", "ou_owner", "p2p", "text", text);
    }

    private static ImInboundMessage msgFrom(String openId, String text) {
        return new ImInboundMessage("oc_1", "om_1", openId, "p2p", "text", text);
    }

    // ── supports：两命令形态命中 ─────────────────────────────────────

    @Test
    @DisplayName("给定裸 6 位码文本，when tryRoute，then抽取码核销并返回成功话术")
    void givenBareSixDigitCode_whenTryRoute_thenBindsAndRepliesSuccess() {
        Optional<String> reply = handler.tryRoute(msg("483920"));

        assertThat(reply).contains("绑定成功：将为你推送个性化盘前简报与重大公告提醒");
        verify(subscriptionService).bindByCode("483920", "ou_owner");
    }

    @Test
    @DisplayName("给定「绑定」前缀带一个空格，when tryRoute，then命中并抽取码核销")
    void givenBindPrefixWithSpace_whenTryRoute_thenBinds() {
        Optional<String> reply = handler.tryRoute(msg("绑定 004512"));

        assertThat(reply).isPresent();
        verify(subscriptionService).bindByCode("004512", "ou_owner");
    }

    @Test
    @DisplayName("给定「绑定」前缀无空格，when tryRoute，then命中并抽取码核销")
    void givenBindPrefixWithoutSpace_whenTryRoute_thenBinds() {
        Optional<String> reply = handler.tryRoute(msg("绑定771203"));

        assertThat(reply).isPresent();
        verify(subscriptionService).bindByCode("771203", "ou_owner");
    }

    @Test
    @DisplayName("给定两端空白包裹的 6 位码，when tryRoute，then trim 后命中")
    void givenWhitespaceWrappedCode_whenTryRoute_thenTrimsAndBinds() {
        Optional<String> reply = handler.tryRoute(msg("  987654 \n"));

        assertThat(reply).isPresent();
        verify(subscriptionService).bindByCode("987654", "ou_owner");
    }

    // ── 非命令透传 ─────────────────────────────────────────────────

    @Test
    @DisplayName("给定普通对话文本，when tryRoute，then返回 empty 且不触碰订阅服务")
    void givenOrdinaryText_whenTryRoute_thenEmptyAndNoServiceCall() {
        assertThat(handler.tryRoute(msg("我的持仓怎么样"))).isEmpty();
        verifyNoInteractions(subscriptionService);
    }

    @Test
    @DisplayName("给定 5 位或 7 位数字或字母串，when tryRoute，then非命令返回 empty")
    void givenNonSixDigitText_whenTryRoute_thenEmpty() {
        assertThat(handler.tryRoute(msg("12345"))).isEmpty();
        assertThat(handler.tryRoute(msg("1234567"))).isEmpty();
        assertThat(handler.tryRoute(msg("abcd12"))).isEmpty();
        verifyNoInteractions(subscriptionService);
    }

    @Test
    @DisplayName("给定「绑定」前缀后双空格，when tryRoute，then超出单空白口径返回 empty")
    void givenBindPrefixDoubleSpace_whenTryRoute_thenEmpty() {
        assertThat(handler.tryRoute(msg("绑定  123456"))).isEmpty();
        verifyNoInteractions(subscriptionService);
    }

    @Test
    @DisplayName("给定非文本消息（text 为 null），when tryRoute，then返回 empty 透传")
    void givenNullText_whenTryRoute_thenEmpty() {
        assertThat(handler.tryRoute(msg(null))).isEmpty();
        verifyNoInteractions(subscriptionService);
    }

    // ── 错误码→话术 ────────────────────────────────────────────────

    @Test
    @DisplayName("给定码不存在/过期/已核销三态（BINDING_CODE_EXPIRED），when tryRoute，then返回无效话术")
    void givenExpiredOrUsedCode_whenTryRoute_thenInvalidSpeech() {
        doThrow(new IntelligenceException(IntelligenceErrorCode.BINDING_CODE_EXPIRED,
                "绑定码无效或已过期"))
                .when(subscriptionService).bindByCode(anyString(), anyString());

        // B10 后同 openId 60s 内连发第二条命令先撞冷却——改用三个不同 openId 保住三态话术映射断言
        assertThat(handler.tryRoute(msgFrom("ou_a", "111111")))
                .contains("绑定码无效或已过期，请在设置页重新生成");
        assertThat(handler.tryRoute(msgFrom("ou_b", "绑定 222222")))
                .contains("绑定码无效或已过期，请在设置页重新生成");
        assertThat(handler.tryRoute(msgFrom("ou_c", "333333")))
                .contains("绑定码无效或已过期，请在设置页重新生成");
    }

    @Test
    @DisplayName("给定 open_id 已绑其他用户（OPEN_ID_TAKEN），when tryRoute，then返回占用话术")
    void givenOpenIdTaken_whenTryRoute_thenTakenSpeech() {
        doThrow(new IntelligenceException(IntelligenceErrorCode.OPEN_ID_TAKEN,
                "该飞书账号已绑定其他用户"))
                .when(subscriptionService).bindByCode(anyString(), anyString());

        assertThat(handler.tryRoute(msg("绑定 654321")))
                .contains("该飞书账号已绑定其他用户");
    }

    // ── B10：绑定命令按 open_id 冷却 ─────────────────────────────

    @Test
    @DisplayName("给定同 openId 60s 内第二条绑定命令，when tryRoute，then回冷却提示且 bindByCode 仅调一次")
    void given同openId60秒内第二条_whenTryRoute_then冷却提示且不重复核销() {
        MutableClock clock = MutableClock.at("2026-10-05T01:00:00Z");
        BindingCommandHandler cooled = new BindingCommandHandler(subscriptionService, clock);

        assertThat(cooled.tryRoute(msg("111111"))).contains("绑定成功：将为你推送个性化盘前简报与重大公告提醒");
        assertThat(cooled.tryRoute(msg("222222"))).contains("操作过于频繁，请稍后再试");

        verify(subscriptionService, times(1)).bindByCode(anyString(), anyString());
        verify(subscriptionService).bindByCode("111111", "ou_owner");
    }

    @Test
    @DisplayName("给定不同 openId 各发一条绑定命令，when tryRoute，then互不影响各自核销")
    void given不同openId_whenTryRoute_then互不影响() {
        MutableClock clock = MutableClock.at("2026-10-05T01:00:00Z");
        BindingCommandHandler cooled = new BindingCommandHandler(subscriptionService, clock);

        assertThat(cooled.tryRoute(msgFrom("ou_a", "111111"))).contains("绑定成功：将为你推送个性化盘前简报与重大公告提醒");
        assertThat(cooled.tryRoute(msgFrom("ou_b", "222222"))).contains("绑定成功：将为你推送个性化盘前简报与重大公告提醒");

        verify(subscriptionService).bindByCode("111111", "ou_a");
        verify(subscriptionService).bindByCode("222222", "ou_b");
    }

    @Test
    @DisplayName("给定时钟推进 61s，when同 openId 再发绑定命令，then冷却期满恢复核销")
    void given时钟推进61秒_whenTryRoute_then恢复放行() {
        MutableClock clock = MutableClock.at("2026-10-05T01:00:00Z");
        BindingCommandHandler cooled = new BindingCommandHandler(subscriptionService, clock);

        cooled.tryRoute(msg("111111"));
        clock.advanceSeconds(61);
        assertThat(cooled.tryRoute(msg("222222"))).contains("绑定成功：将为你推送个性化盘前简报与重大公告提醒");

        verify(subscriptionService).bindByCode("222222", "ou_owner");
    }

    /** 可拨动时钟（冷却测试避免真实墙钟等待；照 IntelligenceTokenBudgetTest 形态）。 */
    private static final class MutableClock extends Clock {
        private volatile long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        static MutableClock at(String literal) {
            return new MutableClock(Instant.parse(literal).toEpochMilli());
        }

        void advanceSeconds(long seconds) {
            millis += seconds * 1_000;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}

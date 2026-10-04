package com.portfolio.invest.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {

    private static final long MINUTE_MILLIS = 60_000L;

    private long now;
    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        now = 1_000_000L;
        limiter = new LoginRateLimiter(5, Duration.ofMinutes(5), Duration.ofMinutes(5), () -> now);
    }

    private OptionalLong blocked(String username) {
        return limiter.blockedForSeconds(username);
    }

    @DisplayName("连续5次失败→第6次判定锁定并给出Retry-After秒数")
    @Test
    void givenFiveFailures_whenCheck_thenBlockedWithRetryAfter() {
        for (int i = 0; i < 5; i++) {
            assertThat(blocked("alice")).isEmpty(); // 前 5 次失败前不锁定
            limiter.onFailure("alice");
        }
        assertThat(blocked("alice")).hasValue(300L); // 锁 5 分钟 = 300s
    }

    @DisplayName("锁定期满自动解锁，解锁后重新计次")
    @Test
    void givenLockExpired_whenCheck_thenUnblockedAndFreshCounter() {
        for (int i = 0; i < 5; i++) {
            limiter.onFailure("alice");
        }
        assertThat(blocked("alice")).isPresent();

        now += 5 * MINUTE_MILLIS + 1; // 锁定期满
        assertThat(blocked("alice")).isEmpty();

        limiter.onFailure("alice"); // 解锁后首次失败重新计次（窗口固定自首次失败起）
        assertThat(blocked("alice")).isEmpty();
    }

    @DisplayName("锁定期内失败调用不延长锁定时长")
    @Test
    void givenLocked_whenMoreFailures_thenLockNotExtended() {
        for (int i = 0; i < 5; i++) {
            limiter.onFailure("alice");
        }
        now += 4 * MINUTE_MILLIS; // 锁定中
        limiter.onFailure("alice");
        now += MINUTE_MILLIS + 1; // 距锁定开始已 5 分钟：若被延长仍应锁定
        assertThat(blocked("alice")).isEmpty();
    }

    @DisplayName("成功登录清零计数")
    @Test
    void givenSuccess_whenCheck_thenCounterCleared() {
        for (int i = 0; i < 4; i++) {
            limiter.onFailure("alice");
        }
        limiter.onSuccess("alice");
        for (int i = 0; i < 4; i++) {
            limiter.onFailure("alice"); // 若非清零，累计 8 次早已锁定
        }
        assertThat(blocked("alice")).isEmpty();
    }

    @DisplayName("失败窗口固定自首次失败起5分钟，窗口过期重新计次")
    @Test
    void givenWindowExpired_whenFailure_thenCounterResets() {
        limiter.onFailure("alice");
        now += 4 * MINUTE_MILLIS;
        for (int i = 0; i < 3; i++) {
            limiter.onFailure("alice");
        }
        assertThat(blocked("alice")).isEmpty(); // 窗口内共 4 次，未达阈

        now += 2 * MINUTE_MILLIS; // 距首次失败 6 分钟：窗口过期
        limiter.onFailure("alice"); // 重新开窗计次
        assertThat(blocked("alice")).isEmpty(); // 仅 1 次，未达阈
    }

    @DisplayName("Retry-After随时间流逝递减（向上取整秒）")
    @Test
    void givenLocked_whenTimePasses_thenRetryAfterDecreases() {
        for (int i = 0; i < 5; i++) {
            limiter.onFailure("alice");
        }
        assertThat(blocked("alice")).hasValue(300L);
        now += MINUTE_MILLIS;
        assertThat(blocked("alice")).hasValue(240L);
    }

    @DisplayName("不同username互不影响，且按小写归一防大小写轮换绕过")
    @Test
    void givenLockedUsername_whenOtherUsername_thenIndependent() {
        for (int i = 0; i < 5; i++) {
            limiter.onFailure("alice");
        }
        assertThat(blocked("bob")).isEmpty();
        assertThat(blocked("ALICE")).isPresent(); // 与 alice 同一键
        limiter.onSuccess("bob"); // no-op 不抛异常
    }
}

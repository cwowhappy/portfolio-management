package com.portfolio.invest.infrastructure.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 令牌桶限流器（时钟与 sleep 可注入，避免真实墙钟等待）。 */
class RateLimiterTest {

    /** 假时钟 + 假 sleep：sleep 时推进假时钟，不真实等待。 */
    private static RateLimiter limiter(double rate, AtomicLong now) {
        return new RateLimiter(rate, now::get, ms -> now.addAndGet(ms * 1_000_000L));
    }

    @DisplayName("非正速率参数抛异常")
    @Test
    void throwsOnNonPositiveRate() {
        assertThatThrownBy(() -> new RateLimiter(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permitsPerSecond");
        assertThatThrownBy(() -> new RateLimiter(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("充足令牌下立即获取成功")
    @Test
    void acquiresImmediatelyWithSufficientTokens() {
        RateLimiter limiter = new RateLimiter(100);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(0)).isTrue();
        }
    }

    @DisplayName("等待后补充到令牌时成功获取")
    @Test
    void acquiresAfterWaitingForTokenRefill() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter limiter = limiter(5, now);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(0)).isTrue();
        }
        now.addAndGet(10_000_000L); // 10ms → 仅补充 0.05 个令牌，凑满还需 ~190ms
        // 预算 1500ms 足够：sleep（假时钟推进）后循环重试，refill 凑满 1 个令牌 → 成功
        assertThat(limiter.tryAcquire(1500)).isTrue();
    }

    @DisplayName("距下一令牌不足一纳秒时按一纳秒等待")
    @Test
    void waitsAtLeastOneNanosecondWhenShortOfNextToken() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter limiter = limiter(5, now);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(0)).isTrue();
        }
        // 距凑满一个令牌仅差 1ns：waitNanos 计算结果截断为 0 → 钳制为 1ns；
        // 但等待预算为 0 → 超过截止时间，获取失败
        now.addAndGet(199_999_999L);
        assertThat(limiter.tryAcquire(0)).isFalse();
    }

    @DisplayName("等待期间线程被中断时返回false")
    @Test
    void returnsFalseWhenInterruptedWhileWaiting() {
        AtomicLong now = new AtomicLong(0);
        // sleep 时中断当前线程：限流器应检测到中断标记并放弃等待
        RateLimiter limiter = new RateLimiter(5, now::get, ms -> Thread.currentThread().interrupt());
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(0)).isTrue();
        }
        try {
            assertThat(limiter.tryAcquire(1000)).isFalse();
        } finally {
            Thread.interrupted(); // 清除中断标记，避免污染同线程后续测试
        }
    }

    @DisplayName("令牌耗尽且等待时间不足时失败")
    @Test
    void failsWhenTokensExhaustedAndWaitTimeInsufficient() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter limiter = limiter(5, now);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(0)).isTrue();
        }
        now.addAndGet(10_000_000L); // 仅过 10ms，5/秒下补充 0.05 个令牌
        assertThat(limiter.tryAcquire(50)).isFalse();
    }

    @DisplayName("等待足够时间后补充令牌成功")
    @Test
    void acquiresAfterWaitingEnoughForTokenRefill() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter limiter = limiter(5, now);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(0)).isTrue();
        }
        now.addAndGet(200_000_000L); // 200ms → 补充 1 个令牌
        assertThat(limiter.tryAcquire(1500)).isTrue();
    }
}

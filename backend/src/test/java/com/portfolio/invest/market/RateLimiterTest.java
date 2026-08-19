package com.portfolio.invest.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 令牌桶限流器（时钟与 sleep 可注入，避免真实墙钟等待）。 */
class RateLimiterTest {

    /** 假时钟 + 假 sleep：sleep 时推进假时钟，不真实等待。 */
    private static RateLimiter limiter(double rate, AtomicLong now) {
        return new RateLimiter(rate, now::get, ms -> now.addAndGet(ms * 1_000_000L));
    }

    @Test
    void 非正速率参数抛异常() {
        assertThatThrownBy(() -> new RateLimiter(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permitsPerSecond");
        assertThatThrownBy(() -> new RateLimiter(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 充足令牌下立即获取成功() {
        RateLimiter limiter = new RateLimiter(100);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(0)).isTrue();
        }
    }

    @Test
    void 令牌耗尽且等待时间不足时失败() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter limiter = limiter(5, now);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(0)).isTrue();
        }
        now.addAndGet(10_000_000L); // 仅过 10ms，5/秒下补充 0.05 个令牌
        assertThat(limiter.tryAcquire(50)).isFalse();
    }

    @Test
    void 等待足够时间后补充令牌成功() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter limiter = limiter(5, now);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(0)).isTrue();
        }
        now.addAndGet(200_000_000L); // 200ms → 补充 1 个令牌
        assertThat(limiter.tryAcquire(1500)).isTrue();
    }
}

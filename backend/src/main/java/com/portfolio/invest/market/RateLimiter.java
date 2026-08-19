package com.portfolio.invest.market;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * 令牌桶限流：保护免费行情接口，默认 5 次/秒。
 * 等待不持锁；时钟与 sleep 均可注入，便于确定性测试（避免真实墙钟等待）。
 */
public class RateLimiter {

    private final double permitsPerSecond;
    private final LongSupplier nanoTime;
    private final LongConsumer sleeper;
    private double tokens;
    private long lastRefillNanos;

    public RateLimiter(double permitsPerSecond) {
        this(permitsPerSecond, System::nanoTime, RateLimiter::sleepUninterruptibly);
    }

    /** 测试注入：自定义纳秒时钟与 sleep（sleep 可推进假时钟，消除真实等待）。 */
    RateLimiter(double permitsPerSecond, LongSupplier nanoTime, LongConsumer sleeper) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond 必须为正数: " + permitsPerSecond);
        }
        this.permitsPerSecond = permitsPerSecond;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
        this.tokens = permitsPerSecond;
        this.lastRefillNanos = nanoTime.getAsLong();
    }

    /** 尝试获取一个令牌；短暂等待（最多 waitMillis），拿不到返回 false。 */
    public boolean tryAcquire(long waitMillis) {
        long deadline = nanoTime.getAsLong() + waitMillis * 1_000_000L;
        while (true) {
            long waitNanos;
            synchronized (this) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return true;
                }
                // 距凑满一个令牌还需多久
                waitNanos = (long) ((1.0 - tokens) / permitsPerSecond * 1_000_000_000.0);
                if (waitNanos < 1) {
                    waitNanos = 1;
                }
            }
            if (nanoTime.getAsLong() + waitNanos > deadline) {
                return false; // 在 waitMillis 内等不到一个令牌
            }
            sleeper.accept(Math.max(1, waitNanos / 1_000_000L));
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
        }
    }

    private void refill() {
        long now = nanoTime.getAsLong();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            tokens = Math.min(permitsPerSecond * 2, tokens + elapsedSeconds * permitsPerSecond);
            lastRefillNanos = now;
        }
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}

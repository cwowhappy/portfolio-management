package com.portfolio.invest.market;

/** 令牌桶限流：保护免费行情接口，默认 5 次/秒。 */
public class RateLimiter {

    private final double permitsPerSecond;
    private double tokens;
    private long lastRefillNanos;

    public RateLimiter(double permitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
        this.tokens = permitsPerSecond;
        this.lastRefillNanos = System.nanoTime();
    }

    /** 尝试获取一个令牌；短暂等待（最多 waitMillis），拿不到返回 false。 */
    public synchronized boolean tryAcquire(long waitMillis) {
        long deadline = System.currentTimeMillis() + waitMillis;
        while (true) {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            tokens = Math.min(permitsPerSecond * 2, tokens + elapsedSeconds * permitsPerSecond);
            lastRefillNanos = now;
        }
    }
}

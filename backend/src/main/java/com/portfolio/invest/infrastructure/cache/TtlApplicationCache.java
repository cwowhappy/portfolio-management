package com.portfolio.invest.infrastructure.cache;

import com.portfolio.invest.application.cache.ApplicationCache;
import com.portfolio.invest.infrastructure.market.TtlCache;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * {@link ApplicationCache} 端口的基础设施实现：委托 TtlCache（有界 LRU + 读时惰性过期）。
 * 线程安全由 TtlCache 的 synchronized 保证。
 */
public class TtlApplicationCache implements ApplicationCache {

    private final TtlCache delegate;

    public TtlApplicationCache(int maxEntries) {
        this.delegate = new TtlCache(maxEntries);
    }

    /** 测试注入：自定义时钟（避免真实墙钟等待）。 */
    public TtlApplicationCache(int maxEntries, LongSupplier nowMillis) {
        this.delegate = new TtlCache(maxEntries, nowMillis);
    }

    @Override
    public <T> T get(String key) {
        return delegate.get(key);
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        delegate.put(key, value, ttl);
    }
}

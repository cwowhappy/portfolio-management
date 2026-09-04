package com.portfolio.invest.infrastructure.cache;

import com.portfolio.invest.application.cache.ApplicationCache;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 极简本地 TTL 缓存：读时惰性过期 + 有界 LRU 淘汰。
 * 键空间可能无界（如搜索按用户输入为 key），故以 maxEntries 上限 + LRU 淘汰防内存耗尽。
 *
 * <p>同时是 {@link ApplicationCache} 端口的基础设施实现（经 {@link CacheConfig} 装配），
 * 也是 market 包行情热缓存（CachedMarketDataService）的底层引擎。线程安全由 synchronized 保证。
 */
public class TtlCache implements ApplicationCache {

    private record CacheEntry(Object value, long expiresAt) {}

    private final int maxEntries;
    private final LongSupplier nowMillis;
    private final Map<String, CacheEntry> map;

    public TtlCache(int maxEntries) {
        this(maxEntries, System::currentTimeMillis);
    }

    /** 测试注入：自定义时钟（避免真实墙钟等待）。 */
    public TtlCache(int maxEntries, LongSupplier nowMillis) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries 必须为正数: " + maxEntries);
        }
        this.maxEntries = maxEntries;
        this.nowMillis = nowMillis;
        // access-order=true：get 即视为最近使用，配合 removeEldestEntry 实现 LRU
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> T get(String key) {
        CacheEntry e = map.get(key);
        if (e == null) {
            return null;
        }
        if (nowMillis.getAsLong() > e.expiresAt()) {
            map.remove(key);
            return null;
        }
        return (T) e.value();
    }

    @Override
    public synchronized void put(String key, Object value, Duration ttl) {
        map.put(key, new CacheEntry(value, nowMillis.getAsLong() + ttl.toMillis()));
    }

    /** 当前条目数（测试/诊断用）。 */
    public synchronized long size() {
        return map.size();
    }
}

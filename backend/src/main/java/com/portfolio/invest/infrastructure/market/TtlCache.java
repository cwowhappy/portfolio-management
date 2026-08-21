package com.portfolio.invest.infrastructure.market;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 极简本地 TTL 缓存：读时惰性过期 + 有界 LRU 淘汰。
 * 键空间可能无界（如搜索按用户输入为 key），故以 maxEntries 上限 + LRU 淘汰防内存耗尽。
 */
public class TtlCache {

    private record CacheEntry(Object value, long expiresAt) {}

    private final int maxEntries;
    private final LongSupplier nowMillis;
    private final Map<String, CacheEntry> map;

    public TtlCache(int maxEntries) {
        this(maxEntries, System::currentTimeMillis);
    }

    /** 测试注入：自定义时钟（避免真实墙钟等待）。 */
    TtlCache(int maxEntries, LongSupplier nowMillis) {
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

    public synchronized void put(String key, Object value, Duration ttl) {
        map.put(key, new CacheEntry(value, nowMillis.getAsLong() + ttl.toMillis()));
    }

    public synchronized long size() {
        return map.size();
    }
}

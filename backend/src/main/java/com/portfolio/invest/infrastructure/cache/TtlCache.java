package com.portfolio.invest.infrastructure.cache;

import com.portfolio.invest.application.cache.ApplicationCache;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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

    /**
     * 缺失/过期时原子加载（computeIfAbsent 形态，MS-29 B9-④）：整段持锁，同 key 并发惊群
     * 仅一次 loader 执行；TTL 语义不变（未过期命中直接返回，过期视为缺失重载并按新 TTL 回写）。
     * loader 返回 null 不缓存（下次仍会重载）；loader 抛异常原样传播且不写入条目。
     * 注意：loader 在缓存锁内执行——调用方（如行情源加载）的耗时会让其他键的读写排队，
     * 这正是惊群收敛的代价与目的（上游行情源本身限流，重复加载比串行更贵）。
     */
    @SuppressWarnings("unchecked")
    public synchronized <T> T getOrLoad(String key, Duration ttl, Supplier<T> loader) {
        CacheEntry e = map.get(key);
        if (e != null && nowMillis.getAsLong() <= e.expiresAt()) {
            return (T) e.value();
        }
        T value = loader.get();
        if (value != null) {
            map.put(key, new CacheEntry(value, nowMillis.getAsLong() + ttl.toMillis()));
        }
        return value;
    }

    /** 当前条目数（测试/诊断用）。 */
    public synchronized long size() {
        return map.size();
    }
}

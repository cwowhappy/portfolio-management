package com.portfolio.invest.market;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** 极简本地 TTL 缓存：读时惰性过期，定时清理极端膨胀的过期条目。 */
public class TtlCache {

    private record Entry(Object value, long expiresAt) {}

    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        Entry e = map.get(key);
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() > e.expiresAt()) {
            map.remove(key);
            return null;
        }
        return (T) e.value();
    }

    public void put(String key, Object value, Duration ttl) {
        map.put(key, new Entry(value, System.currentTimeMillis() + ttl.toMillis()));
    }

    public long size() {
        return map.size();
    }
}

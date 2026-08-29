package com.portfolio.invest.application.cache;

import java.time.Duration;

/**
 * 应用层缓存端口（F2【决策】）：application/web 需要缓存时只依赖本接口，
 * 由 infrastructure 提供有界（maxEntries）实现并经 config 装配，禁止自造 mini-cache。
 */
public interface ApplicationCache {

    /** 命中且未过期返回缓存值，否则返回 null。 */
    <T> T get(String key);

    /** 以给定 TTL 写入；实现必须保证键空间有界（超出即淘汰）。 */
    void put(String key, Object value, Duration ttl);
}

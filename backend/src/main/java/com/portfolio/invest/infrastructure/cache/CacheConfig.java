package com.portfolio.invest.infrastructure.cache;

import com.portfolio.invest.application.cache.ApplicationCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 应用层缓存端口（ApplicationCache）的装配。 */
@Configuration
public class CacheConfig {

    /** 应用级缓存条目上限：估值/探活等键空间很小，1000 足够且防 key 注入撑爆内存。 */
    private static final int MAX_ENTRIES = 1000;

    @Bean
    public ApplicationCache applicationCache() {
        return new TtlApplicationCache(MAX_ENTRIES);
    }
}

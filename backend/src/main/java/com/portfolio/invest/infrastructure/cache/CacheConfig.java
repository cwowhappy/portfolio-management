package com.portfolio.invest.infrastructure.cache;

import com.portfolio.invest.application.cache.ApplicationCache;
import com.portfolio.invest.config.InvestProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 应用层缓存端口（ApplicationCache）的装配：容量/TTL 读 InvestProperties（invest.app-cache.*）。 */
@Configuration
public class CacheConfig {

    @Bean
    public ApplicationCache applicationCache(InvestProperties props) {
        return new TtlApplicationCache(props.getAppCache().getMaxEntries());
    }
}

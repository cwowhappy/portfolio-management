package com.portfolio.invest.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TtlCacheTest {

    @Test
    void returnsValueBeforeExpiry() {
        TtlCache cache = new TtlCache();
        cache.put("k", "v", Duration.ofMinutes(1));
        assertThat(cache.<String>get("k")).isEqualTo("v");
    }

    @Test
    void expiresAfterTtl() throws InterruptedException {
        TtlCache cache = new TtlCache();
        cache.put("k", "v", Duration.ofMillis(50));
        Thread.sleep(80);
        assertThat(cache.<String>get("k")).isNull();
    }

    @Test
    void missingKeyReturnsNull() {
        assertThat(new TtlCache().<String>get("nope")).isNull();
    }
}

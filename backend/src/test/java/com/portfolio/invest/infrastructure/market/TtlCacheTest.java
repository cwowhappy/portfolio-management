package com.portfolio.invest.infrastructure.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TtlCacheTest {

    @Test
    void returnsValueBeforeExpiry() {
        TtlCache cache = new TtlCache(100);
        cache.put("k", "v", Duration.ofMinutes(1));
        assertThat(cache.<String>get("k")).isEqualTo("v");
    }

    @Test
    void expiresAfterTtl() {
        AtomicLong now = new AtomicLong(1_000_000L);
        TtlCache cache = new TtlCache(100, now::get);
        cache.put("k", "v", Duration.ofMillis(50));
        now.addAndGet(51); // 越过 TTL
        assertThat(cache.<String>get("k")).isNull();
    }

    @Test
    void missingKeyReturnsNull() {
        assertThat(new TtlCache(100).<String>get("nope")).isNull();
    }

    @Test
    void evictsOldestWhenExceedingMaxEntries() {
        TtlCache cache = new TtlCache(2);
        cache.put("a", "1", Duration.ofMinutes(1));
        cache.put("b", "2", Duration.ofMinutes(1));
        cache.put("c", "3", Duration.ofMinutes(1)); // 超出上限，淘汰最久未用的 a
        assertThat(cache.<String>get("a")).isNull();
        assertThat(cache.<String>get("b")).isEqualTo("2");
        assertThat(cache.<String>get("c")).isEqualTo("3");
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void accessUpdatesRecency() {
        TtlCache cache = new TtlCache(2);
        cache.put("a", "1", Duration.ofMinutes(1));
        cache.put("b", "2", Duration.ofMinutes(1));
        cache.get("a"); // a 变最近使用
        cache.put("c", "3", Duration.ofMinutes(1)); // 淘汰 b
        assertThat(cache.<String>get("a")).isEqualTo("1");
        assertThat(cache.<String>get("b")).isNull();
        assertThat(cache.<String>get("c")).isEqualTo("3");
    }

    @Test
    void nonPositiveMaxEntriesThrows() {
        assertThatThrownBy(() -> new TtlCache(0)).isInstanceOf(IllegalArgumentException.class);
    }
}

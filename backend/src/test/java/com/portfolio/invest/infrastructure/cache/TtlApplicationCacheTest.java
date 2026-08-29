package com.portfolio.invest.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** ApplicationCache 端口的 TtlCache 实现：命中、过期、有界淘汰。 */
class TtlApplicationCacheTest {

    @Test
    void 写入后未过期可命中() {
        TtlApplicationCache cache = new TtlApplicationCache(10);
        assertThat((Object) cache.get("k1")).isNull();
        cache.put("k1", "v1", Duration.ofMinutes(1));
        assertThat((String) cache.get("k1")).isEqualTo("v1");
    }

    @Test
    void 过期后返回null() {
        AtomicLong now = new AtomicLong(0);
        TtlApplicationCache cache = new TtlApplicationCache(10, now::get);
        cache.put("k1", "v1", Duration.ofSeconds(30));
        now.addAndGet(31_000);
        assertThat((Object) cache.get("k1")).isNull();
    }

    @Test
    void 超出条目上限淘汰最久未访问() {
        TtlApplicationCache cache = new TtlApplicationCache(2);
        cache.put("a", 1, Duration.ofMinutes(1));
        cache.put("b", 2, Duration.ofMinutes(1));
        cache.get("a"); // 触碰 a，使 b 成为最久未访问
        cache.put("c", 3, Duration.ofMinutes(1));
        assertThat((Object) cache.get("b")).isNull();
        assertThat((Integer) cache.get("a")).isEqualTo(1);
        assertThat((Integer) cache.get("c")).isEqualTo(3);
    }
}

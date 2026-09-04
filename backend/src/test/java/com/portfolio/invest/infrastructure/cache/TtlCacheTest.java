package com.portfolio.invest.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TtlCache（ApplicationCache 端口实现 + 行情热缓存引擎）：命中、过期、有界 LRU、容量校验、时钟注入。 */
class TtlCacheTest {

    @DisplayName("写入后未过期可命中")
    @Test
    void hitWhenWrittenAndNotExpired() {
        TtlCache cache = new TtlCache(10);
        assertThat((Object) cache.get("k1")).isNull();
        cache.put("k1", "v1", Duration.ofMinutes(1));
        assertThat((String) cache.get("k1")).isEqualTo("v1");
    }

    @DisplayName("不存在的键返回null")
    @Test
    void returnsNullForMissingKey() {
        assertThat(new TtlCache(100).<String>get("nope")).isNull();
    }

    @DisplayName("过期后返回null")
    @Test
    void returnsNullAfterExpiry() {
        AtomicLong now = new AtomicLong(0);
        TtlCache cache = new TtlCache(10, now::get);
        cache.put("k1", "v1", Duration.ofSeconds(30));
        now.addAndGet(31_000);
        assertThat((Object) cache.get("k1")).isNull();
    }

    @DisplayName("超出条目上限淘汰最久未写入")
    @Test
    void evictsLeastRecentlyWrittenWhenOverCapacity() {
        TtlCache cache = new TtlCache(2);
        cache.put("a", "1", Duration.ofMinutes(1));
        cache.put("b", "2", Duration.ofMinutes(1));
        cache.put("c", "3", Duration.ofMinutes(1)); // 超出上限，淘汰最久未用的 a
        assertThat((Object) cache.get("a")).isNull();
        assertThat((String) cache.get("b")).isEqualTo("2");
        assertThat((String) cache.get("c")).isEqualTo("3");
        assertThat(cache.size()).isEqualTo(2);
    }

    @DisplayName("访问刷新活跃度后淘汰最久未访问")
    @Test
    void evictsLeastRecentlyAccessedAfterAccessRefreshesRecency() {
        TtlCache cache = new TtlCache(2);
        cache.put("a", 1, Duration.ofMinutes(1));
        cache.put("b", 2, Duration.ofMinutes(1));
        cache.get("a"); // 触碰 a，使 b 成为最久未访问
        cache.put("c", 3, Duration.ofMinutes(1));
        assertThat((Object) cache.get("b")).isNull();
        assertThat((Integer) cache.get("a")).isEqualTo(1);
        assertThat((Integer) cache.get("c")).isEqualTo(3);
    }

    @DisplayName("容量非正数抛异常")
    @Test
    void throwsOnNonPositiveCapacity() {
        assertThatThrownBy(() -> new TtlCache(0)).isInstanceOf(IllegalArgumentException.class);
    }
}

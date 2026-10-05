package com.portfolio.invest.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TtlCache（ApplicationCache 端口实现 + 行情热缓存引擎）：命中、过期、有界 LRU、容量校验、时钟注入。 */
class TtlCacheTest {

    @DisplayName("写入后未过期可命中")
    @Test
    void givenEntryWrittenNotExpired_whenGet_thenHit() {
        TtlCache cache = new TtlCache(10);
        assertThat((Object) cache.get("k1")).isNull();
        cache.put("k1", "v1", Duration.ofMinutes(1));
        assertThat((String) cache.get("k1")).isEqualTo("v1");
    }

    @DisplayName("不存在的键返回null")
    @Test
    void givenMissingKey_whenGet_thenReturnsNull() {
        assertThat(new TtlCache(100).<String>get("nope")).isNull();
    }

    @DisplayName("过期后返回null")
    @Test
    void givenEntryExpired_whenGet_thenReturnsNull() {
        AtomicLong now = new AtomicLong(0);
        TtlCache cache = new TtlCache(10, now::get);
        cache.put("k1", "v1", Duration.ofSeconds(30));
        now.addAndGet(31_000);
        assertThat((Object) cache.get("k1")).isNull();
    }

    @DisplayName("超出条目上限淘汰最久未写入")
    @Test
    void givenCacheOverCapacity_whenPut_thenEvictsLeastRecentlyWritten() {
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
    void givenAccessRefreshesRecency_whenOverCapacity_thenEvictsLeastRecentlyAccessed() {
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
    void givenNonPositiveCapacity_whenNewCache_thenThrows() {
        assertThatThrownBy(() -> new TtlCache(0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ── getOrLoad 原子加载（MS-29 B9-④：get-then-put 惊群收敛为 computeIfAbsent 形态）──

    @DisplayName("getOrLoad 命中未过期条目：不执行 loader 直接返回缓存值")
    @Test
    void givenEntryWrittenNotExpired_whenGetOrLoad_thenLoaderSkipped() {
        TtlCache cache = new TtlCache(10);
        cache.put("k1", "v1", Duration.ofMinutes(1));
        AtomicInteger loads = new AtomicInteger();

        String v = cache.getOrLoad("k1", Duration.ofMinutes(1), () -> {
            loads.incrementAndGet();
            return "reloaded";
        });

        assertThat(v).isEqualTo("v1");
        assertThat(loads.get()).as("命中时 loader 不执行").isZero();
    }

    @DisplayName("getOrLoad 过期条目：重载并按新 TTL 回写（后续命中不再重载）")
    @Test
    void givenEntryExpired_whenGetOrLoad_thenReloadsAndCaches() {
        AtomicLong now = new AtomicLong(0);
        TtlCache cache = new TtlCache(10, now::get);
        cache.put("k1", "v1", Duration.ofSeconds(30));
        now.addAndGet(31_000); // 过期
        AtomicInteger loads = new AtomicInteger();

        String reloaded = cache.getOrLoad("k1", Duration.ofSeconds(30), () -> {
            loads.incrementAndGet();
            return "v2";
        });

        assertThat(reloaded).isEqualTo("v2");
        assertThat(loads.get()).isOne();
        String again = cache.getOrLoad("k1", Duration.ofSeconds(30), () -> {
            loads.incrementAndGet();
            return "v3";
        });
        assertThat(again).as("重载值已按 TTL 回写，二次读取命中").isEqualTo("v2");
        assertThat(loads.get()).isOne();
    }

    @DisplayName("getOrLoad 同 key 多线程惊群：仅一次 loader 执行，各线程同值返回")
    @Test
    void givenConcurrentSameKeyMisses_whenGetOrLoad_thenLoaderRunsExactlyOnce() throws Exception {
        TtlCache cache = new TtlCache(10);
        AtomicInteger loads = new AtomicInteger();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(); // 齐放起跑，最大化惊群窗口
                    return cache.getOrLoad("k1", Duration.ofMinutes(1), () -> {
                        loads.incrementAndGet();
                        try {
                            Thread.sleep(50); // 拉宽加载窗口：get-then-put 形态下必然多次进入
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        return "v1";
                    });
                }));
            }
            for (Future<String> f : futures) {
                assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo("v1");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(loads.get()).as("同 key 惊群仅一次 loader 执行").isEqualTo(1);
    }

    @DisplayName("getOrLoad loader 返回 null：不缓存，下次仍会重载")
    @Test
    void givenNullLoadingResult_whenGetOrLoad_thenNotCachedAndReloadedNextTime() {
        TtlCache cache = new TtlCache(10);
        AtomicInteger loads = new AtomicInteger();

        String first = cache.getOrLoad("k1", Duration.ofMinutes(1), () -> {
            loads.incrementAndGet();
            return null;
        });

        assertThat(first).isNull();
        assertThat(cache.size()).as("null 不落条目").isZero();
        String second = cache.getOrLoad("k1", Duration.ofMinutes(1), () -> {
            loads.incrementAndGet();
            return "v2";
        });
        assertThat(second).isEqualTo("v2");
        assertThat(loads.get()).as("null 未缓存，第二次仍执行 loader").isEqualTo(2);
    }

    @DisplayName("getOrLoad loader 抛异常：原样传播且不写入条目")
    @Test
    void givenLoaderThrows_whenGetOrLoad_thenPropagatesAndNotCached() {
        TtlCache cache = new TtlCache(10);

        assertThatThrownBy(() -> cache.getOrLoad("k1", Duration.ofMinutes(1),
                () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
        assertThat(cache.size()).as("失败加载不留半成品条目").isZero();
    }
}

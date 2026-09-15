package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CurrentUserHolder 的 ThreadLocal 语义：同线程 set/get/remove 回读，跨线程值隔离。 */
class CurrentUserHolderTest {

    /** 池化线程复用防串值：每测试后清理本线程 ThreadLocal（含失败路径）。 */
    @AfterEach
    void cleanUp() {
        CurrentUserHolder.remove();
    }

    @DisplayName("set后get回读同一userId")
    @Test
    void givenValueSet_whenGet_thenReturnsValue() {
        CurrentUserHolder.set(1L);
        assertThat(CurrentUserHolder.get()).isEqualTo(1L);
    }

    @DisplayName("未set时线程初始get为null")
    @Test
    void givenNoValue_whenGet_thenReturnsNull() {
        assertThat(CurrentUserHolder.get()).isNull();
    }

    @DisplayName("set后remove则get回null")
    @Test
    void givenValueSet_whenRemove_thenGetReturnsNull() {
        CurrentUserHolder.set(1L);
        CurrentUserHolder.remove();
        assertThat(CurrentUserHolder.get()).isNull();
    }

    @DisplayName("两线程各set不同值互不串扰")
    @Test
    void givenTwoThreads_whenSetDifferentValues_thenIsolated() throws InterruptedException {
        CountDownLatch bothObserved = new CountDownLatch(2);
        AtomicReference<Long> seenByThreadA = new AtomicReference<>();
        AtomicReference<Long> seenByThreadB = new AtomicReference<>();
        Thread threadA = new Thread(() -> {
            CurrentUserHolder.set(1L);
            seenByThreadA.set(CurrentUserHolder.get()); // 在线程 A 内回读
            bothObserved.countDown();
        });
        Thread threadB = new Thread(() -> {
            CurrentUserHolder.set(2L);
            seenByThreadB.set(CurrentUserHolder.get()); // 在线程 B 内回读
            bothObserved.countDown();
        });
        threadA.start();
        threadB.start();

        // latch 确定性会合：两线程各自回读完成后主线程才断言，无真实 sleep
        assertThat(bothObserved.await(2, TimeUnit.SECONDS)).isTrue();
        threadA.join();
        threadB.join();

        assertThat(seenByThreadA.get()).isEqualTo(1L);
        assertThat(seenByThreadB.get()).isEqualTo(2L);
        // 主线程视角隔离：子线程的写值不泄漏到未 set 的主线程
        assertThat(CurrentUserHolder.get()).isNull();
    }
}

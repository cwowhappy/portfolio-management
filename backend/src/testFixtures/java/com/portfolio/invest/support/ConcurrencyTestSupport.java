package com.portfolio.invest.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发集成测试基座（testFixtures 共享，供 integrationTest 的并发回归测试复用）。
 *
 * <p>沉淀各并发测试的公共能力：{@link JdbcTemplate} 注入（直插提交态种子、SQL 断言）、
 * 高位哨兵用户 id（避开 Flyway/seed 数据与自增序列，防 id 冲突）、以及
 * {@link #race} 齐放工具（CyclicBarrier 让 N 个线程同时起跑并收集结果）。
 *
 * <p>各测试类的种子链结构与清理顺序差异较大（外键逆序删多表 vs 按用户名删单行），
 * 仍由子类自己的 {@code @BeforeEach}/{@code @AfterEach} 负责，不做强行抽象。
 */
public abstract class ConcurrencyTestSupport extends PostgresTestSupport {

    /** 高位哨兵 id 区：避开种子数据与自增序列，各并发测试取用不同值。 */
    protected static final long SENTINEL_ID_9001 = 9001L;
    protected static final long SENTINEL_ID_9003 = 9003L;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /** 直插提交态用户行（并发线程可见，供 portfolio.user_id 等外键引用）。 */
    protected void insertUser(long id, String username) {
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, ?, ?, ?)",
                id, username, "h", "USER", "PENDING");
    }

    /**
     * CyclicBarrier 齐放：{@code threads} 个线程同时起跑执行 {@code task}，
     * 任一任务抛异常（如并发下的约束冲突）都在 future.get 处原样暴露使测试失败。
     */
    protected List<Future<Void>> race(int threads, Callable<Void> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return task.call();
                }));
            }
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
            return futures;
        } finally {
            pool.shutdownNow();
        }
    }
}

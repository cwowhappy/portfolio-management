package com.portfolio.invest.application.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.infrastructure.persistence.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发首次访问的幂等回归：前端 /portfolio 首次加载会并发触发多个读接口，
 * 各接口都走 getOrCreatePortfolio，修复前 find→save 非原子导致唯一约束冲突（400）。
 */
class PortfolioConcurrencyIntegrationTest extends IntegrationTestBase {

    @Autowired
    private PortfolioApplicationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final long USER_ID = 9001L;

    /** portfolio.user_id 外键引用 app_user(id)，需先植入带指定 id 的用户行（提交态，供并发线程可见）。 */
    @BeforeEach
    void seedUser() {
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, ?, ?, ?)",
                USER_ID, "portfolio-concurrency", "h", "USER", "PENDING");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM portfolio WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", USER_ID);
    }

    @Test
    void 并发首次访问组合只创建一行() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    barrier.await();
                    service.groups(USER_ID);
                    return null;
                }));
            }
            for (Future<Void> future : futures) {
                // 任一并发请求抛异常（修复前为 DataIntegrityViolationException）都会在这里暴露
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM portfolio WHERE user_id = ?", Integer.class, USER_ID);
        assertThat(count).isEqualTo(1);
    }
}

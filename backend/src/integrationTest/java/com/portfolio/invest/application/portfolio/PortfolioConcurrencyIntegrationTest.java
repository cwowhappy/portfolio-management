package com.portfolio.invest.application.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.support.ConcurrencyTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 并发首次访问的幂等回归：前端 /portfolio 首次加载会并发触发多个读接口，
 * 各接口都走 getOrCreatePortfolio，修复前 find→save 非原子导致唯一约束冲突（400）。
 */
@SpringBootTest
class PortfolioConcurrencyIntegrationTest extends ConcurrencyTestSupport {

    @Autowired
    private PortfolioApplicationService service;

    private static final long USER_ID = SENTINEL_ID_9001;

    /** portfolio.user_id 外键引用 app_user(id)，需先植入带指定 id 的用户行（提交态，供并发线程可见）。 */
    @BeforeEach
    void seedUser() {
        insertUser(USER_ID, "portfolio-concurrency");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM portfolio WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", USER_ID);
    }

    @DisplayName("并发首次访问组合只创建一行")
    @Test
    void whenConcurrentFirstAccess_thenSinglePortfolioRowCreated() throws Exception {
        // 任一并发请求抛异常（修复前为 DataIntegrityViolationException）都会在 race 的 future.get 暴露
        race(8, () -> {
            service.groups(USER_ID);
            return null;
        });

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM portfolio WHERE user_id = ?", Integer.class, USER_ID);
        assertThat(count).isEqualTo(1);
    }
}

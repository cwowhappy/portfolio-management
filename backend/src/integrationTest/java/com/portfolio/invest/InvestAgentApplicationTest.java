package com.portfolio.invest;

import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring 上下文冒烟：无论是否配置 DEEPSEEK_API_KEY 都应能启动（MOCK 环境不占端口）。
 * 继承 Testcontainers 基座，使测试自包含于真实 PostgreSQL，不依赖外部数据库。
 */
@SpringBootTest
class InvestAgentApplicationTest extends PostgresTestSupport {

    @Test
    void contextLoads() {
        // 上下文装配成功即通过
    }
}

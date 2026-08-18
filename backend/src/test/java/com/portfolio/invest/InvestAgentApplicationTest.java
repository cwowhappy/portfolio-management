package com.portfolio.invest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Spring 上下文冒烟：无论是否配置 DEEPSEEK_API_KEY 都应能启动（MOCK 环境不占端口）。 */
@SpringBootTest
class InvestAgentApplicationTest {

    @Test
    void contextLoads() {
        // 上下文装配成功即通过
    }
}

package com.portfolio.invest.infrastructure.persistence;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Testcontainers 基座。
 *
 * <p>Postgres 容器作为 JVM 级单例，整个测试进程只启动一次、进程退出时停掉：多个集成测试类
 * 共享同一个 Spring 上下文缓存时，@DynamicPropertySource 只会被解析一次，若用 JUnit 的
 * {@code @Container}（每个测试类启停、重启后端口会变），后续类会连到已停掉的旧端口。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop));
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}

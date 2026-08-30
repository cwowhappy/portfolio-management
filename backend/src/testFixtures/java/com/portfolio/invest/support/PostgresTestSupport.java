package com.portfolio.invest.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Testcontainers 基座（testFixtures 共享，供 integrationTest / bdd 复用）。
 *
 * <p>Postgres 容器作为 JVM 级单例，整个测试进程只启动一次、进程退出时停掉：多个集成测试类
 * 共享同一个 Spring 上下文缓存时，@DynamicPropertySource 只会被解析一次，若用 JUnit 的
 * {@code @Container}（每个测试类启停、重启后端口会变），后续类会连到已停掉的旧端口。
 *
 * <p>基座不挂 {@code @SpringBootTest}/{@code @AutoConfigureMockMvc}：@DataJpaTest 等切片
 * 不需要完整上下文，这两个注解由各集成测试类按需自行声明。
 */
public abstract class PostgresTestSupport {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop));
    }

    /** 暴露 JVM 单例容器，供切片测试以 {@code @ServiceConnection} 复用（避免再启一个容器）。 */
    public static PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}

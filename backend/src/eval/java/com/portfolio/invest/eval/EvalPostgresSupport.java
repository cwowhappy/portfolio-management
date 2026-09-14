package com.portfolio.invest.eval;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 评估侧 Testcontainers PG（自建单例，不动 testFixtures 的 PostgresTestSupport，也不动本机 dev 库）。
 *
 * <p>与 integrationTest 基座同型：JVM 级单例容器、进程退出时停掉、禁用 Ryuk（Colima 类运行时
 * socket 挂载限制，环境变量由 evalAgent 任务注入）。差别：评估不走 @DynamicPropertySource
 * （非测试框架），JDBC 参数由 {@link EvalRunner} 显式传入 SpringApplicationBuilder。
 */
public final class EvalPostgresSupport {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withCommand("-c", "max_connections=300");

    static {
        POSTGRES.start();
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop));
    }

    private EvalPostgresSupport() {}

    /** 触发类加载即启动容器；返回单例。 */
    public static PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    public static String username() {
        return POSTGRES.getUsername();
    }

    public static String password() {
        return POSTGRES.getPassword();
    }
}

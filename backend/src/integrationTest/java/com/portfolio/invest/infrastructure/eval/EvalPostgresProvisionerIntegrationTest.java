package com.portfolio.invest.infrastructure.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.support.PostgresTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 评测独立 schema 供给器对真 PG 的重置契约（MS-30 B2，设计规格 §2.3「同库独立 schema 每轮重置」）：
 * reset 经 DriverManager 直连执行 DROP SCHEMA IF EXISTS eval_schema CASCADE + CREATE SCHEMA，
 * 返回携带 {@code currentSchema=eval_schema,public} 的 URL（末位 public 是 V3 pg_trgm 算子类
 * 解析的硬前提——PG JDBC 的 currentSchema 会整体替换 search_path），且幂等（二次 reset 不报错）。
 *
 * <p>纯 JDBC 无 Spring 上下文（重置发生在子进程 main 内、上下文启动前）。端到端项按设计规格
 * 测试矩阵「独立 schema 重置端到端（起真 schema 跑 Flyway 断言表数）」：用返回 URL 程序化跑
 * Flyway V1~V5，钉死全部表与 history 表落 eval_schema、public 不受波及——首跑冒烟正是靠
 * 这条拦住了「search_path 缺 public 导致 V3 gin_trgm_ops 解析失败」。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvalPostgresProvisionerIntegrationTest extends PostgresTestSupport {

    /**
     * 共享容器卫生（Task 2 引入、Task 3 全量 check 暴露）：端到端用例把 V1~V5 全量迁进
     * eval_schema 后不回收，而同容器的 IntelligenceMigrationTest 以不带 schema 限定的
     * information_schema 计数断言表唯一——两类执行顺序翻转（重编译使类发现序重排）即双份
     * 计数失败（clean HEAD 复现：expected 1 but was 2）。@AfterAll 整体 DROP 回收，恢复
     * 「容器内只有 public 业务 schema」的共享前提，两种类执行顺序下均确定（与下方 pg_trgm
     * 钉入 public 的秩序观同款）；pg_trgm 是库级对象且各处建法均为 IF NOT EXISTS，保留无害。
     */
    @AfterAll
    void whenClassDone_thenDropEvalSchema() throws SQLException {
        PostgreSQLContainer<?> pg = PostgresTestSupport.postgres();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS eval_schema CASCADE");
        }
    }

    @Test
    @DisplayName("reset 清空并重建 eval_schema 且返回 URL 携带评测 search_path（幂等）")
    void whenResetTwice_thenDropsAndRecreatesSchemaAndUrlCarriesCurrentSchema() throws SQLException {
        PostgreSQLContainer<?> pg = PostgresTestSupport.postgres();
        String url = EvalPostgresProvisioner.reset(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        assertThat(url).contains("currentSchema=eval_schema");

        // 第一轮 reset 后在 eval_schema 制造残留（经返回 URL 的 currentSchema 落 schema，模拟上一轮跑过的表）
        try (Connection c = DriverManager.getConnection(url, pg.getUsername(), pg.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE residue_marker(id int)");
        }
        assertThat(baseTableCount(pg, "eval_schema")).isEqualTo(1);

        // 幂等：二次 reset 不报错，且 DROP CASCADE 清掉残留（Flyway 未跑前 schema 应为空）
        String urlAgain = EvalPostgresProvisioner.reset(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        assertThat(urlAgain).contains("currentSchema=eval_schema");
        assertThat(baseTableCount(pg, "eval_schema")).isZero();
    }

    @Test
    @DisplayName("reset 只重建 eval_schema——同库其他 schema 的既有对象不受影响")
    void whenReset_thenOtherSchemasUntouched() throws SQLException {
        PostgreSQLContainer<?> pg = PostgresTestSupport.postgres();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS public.provisioner_public_marker(id int)");
        }
        try {
            EvalPostgresProvisioner.reset(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            assertThat(baseTableCount(pg, "public")).isGreaterThanOrEqualTo(1);
        } finally {
            try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS public.provisioner_public_marker");
            }
        }
    }

    @Test
    @DisplayName("端到端：reset 后经返回 URL 跑 Flyway V1~V5——表全落 eval_schema 且 public 不增")
    void whenFlywayMigratesViaReturnedUrl_thenAllTablesLandInEvalSchemaOnly() throws SQLException {
        PostgreSQLContainer<?> pg = PostgresTestSupport.postgres();
        // 前置复刻「同库已装 pg_trgm 于 public」的 dev/deploy 库前提（侦察结论 §6）：扩展是库级对象，
        // 全新容器上若由本测试的 eval 连接首发安装会落 search_path 首位 eval_schema——既偏离真实
        // 部署形态，又会污染共享容器的其他测试类（其 public 上下文的 IF NOT EXISTS 变 no-op 后
        // 解析不到 gin_trgm_ops）。经默认 search_path 连接钉进 public，两种类执行顺序下均确定。
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        }
        String evalUrl = EvalPostgresProvisioner.reset(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        int publicTablesBefore = baseTableCount(pg, "public");

        // 子进程内上下文启动等价路径：Flyway 自动配置按连接 search_path 首位定位默认 schema
        Flyway.configure()
                .dataSource(evalUrl, pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // history 表与业务表全落 eval_schema（业务表 69 = 侦察口径 65（V5 前）+ MS-30 V5 四表，
        // 另有 flyway_schema_history 本体；表数即防漂断言——后续迁移加表须同步更新此口径）
        assertThat(schemaExists(pg, "eval_schema", "flyway_schema_history")).isTrue();
        assertThat(businessTableCount(pg, "eval_schema")).isEqualTo(69);
        // 同库隔离：public 的表数不增（Flyway 未被 search_path 带偏）
        assertThat(baseTableCount(pg, "public")).isEqualTo(publicTablesBefore);
    }

    private boolean schemaExists(PostgreSQLContainer<?> pg, String schema, String table) throws SQLException {
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT to_regclass(?::text) IS NOT NULL")) {
            // to_regclass 按 search_path 解析，这里显式限定 schema
            ps.setString(1, schema + "." + table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private int baseTableCount(PostgreSQLContainer<?> pg, String schema) throws SQLException {
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** 业务表数（排除 flyway_schema_history 迁移账本本体）。 */
    private int businessTableCount(PostgreSQLContainer<?> pg, String schema) throws SQLException {
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM information_schema.tables WHERE table_schema = ?"
                             + " AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}

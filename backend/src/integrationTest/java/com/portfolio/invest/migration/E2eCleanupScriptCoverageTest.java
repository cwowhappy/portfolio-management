package com.portfolio.invest.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.support.PostgresTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * C5 第②条的机械强制（MS-11 偏差 #8 根因闭环）：scripts/e2e-cleanup.sh 的 DELETE 表集合
 * 必须覆盖 information_schema 中所有引用 app_user(id) 的 FK 表——漏一张，DELETE FROM app_user
 * 就被 FK 击穿、dev 库 e2e 用户永久累积（2026-09-22 实积 56 个的复发路径）。
 * 之前该不变式只存在于规范文字（02-后端架构与代码规范 C5），本测试把它钉进构建。
 * 已知限制：脚本文件不在 gradle source set 输入里，本地增量跑可能 UP-TO-DATE 跳过——
 * 改动 e2e-cleanup.sh 后须 `--rerun`（或 cleanTest）；CI 全新构建不受影响。变异已验证可拦。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class E2eCleanupScriptCoverageTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DisplayName("e2e-cleanup.sh 的 DELETE 表覆盖全部 app_user 外键表（C5②机械强制）")
    @Test
    void whenMigrated_thenCleanupScriptCoversAllUserForeignKeyTables() throws IOException {
        Set<String> fkTables = new LinkedHashSet<>(jdbcTemplate.queryForList("""
                SELECT tc.table_name
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
                  JOIN information_schema.constraint_column_usage ccu
                    ON tc.constraint_name = ccu.constraint_name AND tc.table_schema = ccu.table_schema
                 WHERE tc.constraint_type = 'FOREIGN KEY'
                   AND ccu.table_name = 'app_user' AND ccu.column_name = 'id'
                 ORDER BY tc.table_name
                """, String.class));

        Set<String> scriptTables = cleanupDeleteTables();
        // app_user 本身也在脚本里（最后自删），但不是 FK 引用方——只校验外键表都被覆盖
        assertThat(scriptTables)
                .as("e2e-cleanup.sh 须为每张 user_id 外键表配 DELETE（C5②）；缺失: %s",
                        fkTables.stream().filter(t -> !scriptTables.contains(t)).toList())
                .containsAll(fkTables);
    }

    /** 解析 ../scripts/e2e-cleanup.sh 中全部 `DELETE FROM <table>` 表名（gradle workingDir=backend）。 */
    private static Set<String> cleanupDeleteTables() throws IOException {
        Path script = Path.of("..", "scripts", "e2e-cleanup.sh");
        assertThat(Files.exists(script)).as("脚本存在: %s", script.toAbsolutePath()).isTrue();
        List<String> lines = Files.readAllLines(script);
        Pattern p = Pattern.compile("DELETE FROM ([a-z_]+)");
        Set<String> tables = new LinkedHashSet<>();
        for (String line : lines) {
            Matcher m = p.matcher(line);
            if (m.find()) tables.add(m.group(1));
        }
        return tables;
    }
}

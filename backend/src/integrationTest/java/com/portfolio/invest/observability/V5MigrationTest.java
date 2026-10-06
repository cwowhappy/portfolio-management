package com.portfolio.invest.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.support.PostgresTestSupport;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V5__ms30_eval_observability.sql 迁移契约（MS-30 B1）：@SpringBootTest 在真实 PG
 * （Testcontainers）上跑全量迁移（V1~V5）后断言——eval_run / prompt_asset_version /
 * tool_invocation_obs / turn_observation 四表存在、eval_run 的恒一基准部分唯一索引生效
 * （第二条 baseline=true 必被拒）。DDL 逐字对照设计规格 §六（features/eval-observability）。
 *
 * <p>观测两表 user_id 弱引用无 FK 是设计性规避：不引用 app_user(id) 即不触发
 * {@code E2eCleanupScriptCoverageTest} 的 e2e-cleanup.sh DELETE 登记义务——本类第二条
 * 测试把该决策钉进构建，防后续有人「补全 FK」时静默破坏 e2e-cleanup 契约。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V5MigrationTest extends PostgresTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    /**
     * 本类为非 @Transactional 直插（autocommit）且共享 PG 容器跨类可见：清掉自建行，
     * 保持与其余测试类顺序无关。baseline 唯一部分索引是全表级约束，残留 baseline=true
     * 行会让后续断言顺序敏感（本类当前单测试方法写 eval_run，防御性全清）。
     */
    @AfterEach
    void cleanUpCommittedFixtures() {
        jdbc.update("DELETE FROM eval_run");
        jdbc.update("DELETE FROM prompt_asset_version");
        jdbc.update("DELETE FROM tool_invocation_obs");
        jdbc.update("DELETE FROM turn_observation");
    }

    @Test
    @DisplayName("V5 建四张评测/观测表且恒一基准部分唯一索引生效（第二条 baseline 被拒）")
    void whenV5Applied_thenCreatesFourTablesAndEnforcesSingleBaseline() {
        for (String t : List.of("eval_run", "prompt_asset_version", "tool_invocation_obs", "turn_observation")) {
            assertThat(jdbc.queryForObject("SELECT to_regclass('public." + t + "')", String.class))
                    .as("表 %s 应由 V5 创建", t)
                    .isNotNull();
        }
        // 唯一 baseline 部分索引：两条 baseline 必被拒（恒一基准）
        jdbc.update("INSERT INTO eval_run(triggered_by, status) VALUES ('SCHEDULED','COMPLETED')");
        jdbc.update("UPDATE eval_run SET baseline = true WHERE id = (SELECT max(id) FROM eval_run)");
        assertThatThrownBy(() -> {
            jdbc.update("INSERT INTO eval_run(triggered_by, status, baseline) VALUES ('SCHEDULED','COMPLETED', true)");
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("观测两表 user_id 无 app_user 外键（e2e-cleanup 登记义务设计性规避）")
    void whenObservabilityTablesCreated_thenUserIdHasNoForeignKeyToAppUser() {
        Integer fkCount = jdbc.queryForObject("""
                SELECT count(*)
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
                  JOIN information_schema.constraint_column_usage ccu
                    ON tc.constraint_name = ccu.constraint_name AND tc.table_schema = ccu.table_schema
                 WHERE tc.constraint_type = 'FOREIGN KEY' AND ccu.table_name = 'app_user'
                   AND ccu.column_name = 'id'
                   AND tc.table_name IN ('tool_invocation_obs', 'turn_observation')
                """, Integer.class);
        assertThat(fkCount)
                .as("观测表不得挂 app_user 外键——挂上即触发 e2e-cleanup.sh DELETE 登记义务（C5②），"
                        + "设计决策：观测旁路数据随保留期自然过期")
                .isZero();
    }
}

package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.application.intelligence.CollectorRunInspectPort;
import com.portfolio.invest.application.intelligence.CollectorRunInspectPort.TaskRunSummary;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CollectorRunInspectPort 真库契约（Testcontainers PG16）：collector_task_run 终态
 * 过滤（finished_at IS NOT NULL 剔 running）、task_code JOIN 过滤、65 天窗口外剔除、
 * started_at 升序、表不可读降级空列表（冷启动不抛）。
 *
 * <p>collector_task / collector_task_run 为 collector Alembic 建的<b>跨服务运维表，
 * 不入 backend Flyway</b>（backend 侧只读，trading_calendar 同款先例）——集成库只有
 * Flyway 表，故测试内按 collector/migrations/versions/0001_baseline.py 的真实 DDL
 * 自建两表（@BeforeAll 建表 + @AfterAll 删表，测试自建自清，不污染共享容器）。
 * run 时刻全部取 now() 相对偏移（固定日期会随时间漂出 65 天窗口）。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CollectorRunInspectPortImplTest extends PostgresTestSupport {

    /** 目标任务（collector/tasks/macro_afmi.yaml）。 */
    private static final String TASK_CODE = "macro_afmi";

    @Autowired
    CollectorRunInspectPort port;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    void createCollectorTables() {
        // DDL 逐列照抄 Alembic 0001_baseline.py（JOIN 需要两表，全列照搬）
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS collector_task (
                    id BIGSERIAL PRIMARY KEY,
                    task_code VARCHAR(64) NOT NULL UNIQUE,
                    task_name VARCHAR(128) NOT NULL,
                    source_ids JSONB NOT NULL,
                    converter VARCHAR(64) NOT NULL,
                    calc VARCHAR(64),
                    validator JSONB,
                    target_table VARCHAR(64) NOT NULL,
                    schedule JSONB NOT NULL,
                    enabled BOOLEAN NOT NULL DEFAULT true,
                    trading_day_gated BOOLEAN NOT NULL DEFAULT true,
                    retry_max INT NOT NULL DEFAULT 3,
                    retry_backoff VARCHAR(16) NOT NULL DEFAULT 'exponential',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                CREATE TABLE IF NOT EXISTS collector_task_run (
                    id BIGSERIAL PRIMARY KEY,
                    task_id BIGINT NOT NULL REFERENCES collector_task(id),
                    mode VARCHAR(16) NOT NULL DEFAULT 'incremental',
                    status VARCHAR(16) NOT NULL,
                    source_used VARCHAR(64),
                    params JSONB,
                    rows_written INT,
                    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    finished_at TIMESTAMPTZ,
                    error TEXT,
                    message TEXT,
                    rows_affected INT
                );
                """);
    }

    @AfterAll
    void dropCollectorTables() {
        jdbc.execute("DROP TABLE IF EXISTS collector_task_run, collector_task");
    }

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM collector_task_run");
        jdbc.update("DELETE FROM collector_task");
    }

    @Test
    @DisplayName("runsOf：只回该任务窗口内终态行（剔 running/他任务/窗口外），started_at 升序")
    void givenMixedRuns_whenRunsOf_thenTerminalRunsOfTaskInWindowAscending() {
        long afmi = insertTask(TASK_CODE);
        long other = insertTask("macro_cpi");
        // 窗口外旧 run（70 天前终态）：不回
        insertRun(afmi, "failed", null, "now() - interval '70 days'");
        // running 前置行（finished_at NULL）：未终态不回
        insertRunningRow(afmi, "now() - interval '1 day'");
        // 他任务终态行：不回
        insertRun(other, "success", "stats", "now() - interval '3 days'");
        // 目标任务三终态行（success 带 source_used / AllSourcesFailed 为 NULL / m2 兜底）
        insertRun(afmi, "success", "socfin", "now() - interval '20 days'");
        insertRun(afmi, "failed", null, "now() - interval '19 days'");
        insertRun(afmi, "success", "m2", "now() - interval '18 days'");

        List<TaskRunSummary> runs = port.runsOf(TASK_CODE, 65);

        assertThat(runs).extracting(TaskRunSummary::status)
                .containsExactly("success", "failed", "success"); // started_at 升序
        assertThat(runs).extracting(TaskRunSummary::sourceUsed)
                .containsExactly("socfin", null, "m2");
        List<Instant> startedAts = runs.stream().map(TaskRunSummary::startedAt).toList();
        assertThat(startedAts).isSorted();
        assertThat(startedAts.get(0)).isAfter(Instant.now().minusSeconds(21 * 86400));
    }

    @Test
    @DisplayName("runsOf：任务无任何 run，then空列表")
    void givenTaskWithoutRuns_whenRunsOf_thenEmpty() {
        insertTask(TASK_CODE);

        assertThat(port.runsOf(TASK_CODE, 65)).isEmpty();
    }

    @Test
    @DisplayName("runsOf：表不存在（backend 独立部署冷启动），then降级空列表不抛")
    void givenTablesMissing_whenRunsOf_thenEmptyWithoutThrowing() {
        jdbc.execute("DROP TABLE collector_task_run, collector_task");

        assertThat(port.runsOf(TASK_CODE, 65)).isEmpty();

        // 重建供后续用例（@AfterAll 统一回收）
        createCollectorTables();
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** 建任务行（NOT NULL 列给最小值），返回 task_id。 */
    private long insertTask(String taskCode) {
        jdbc.update("""
                INSERT INTO collector_task (task_code, task_name, source_ids, converter, target_table, schedule)
                VALUES (?, '测试任务', '[]', 'x', 'x', '{}')
                """, taskCode);
        return jdbc.queryForObject("SELECT id FROM collector_task WHERE task_code = ?", Long.class, taskCode);
    }

    /** 建 run 行：status / source_used 参数化，started_at 为 now() 相对表达式（受控常量拼接），finished_at 置终态。 */
    private void insertRun(long taskId, String status, String sourceUsed, String startedAtExpr) {
        jdbc.update("INSERT INTO collector_task_run (task_id, status, source_used, started_at, finished_at)"
                        + " VALUES (?, ?, ?, " + startedAtExpr + ", " + startedAtExpr + " + interval '1 minute')",
                taskId, status, sourceUsed);
    }

    /** 建 running 前置行（finished_at NULL——TaskRunner 执行前插入、终态时回填）。 */
    private void insertRunningRow(long taskId, String startedAtExpr) {
        jdbc.update("INSERT INTO collector_task_run (task_id, status, started_at, finished_at)"
                + " VALUES (?, 'running', " + startedAtExpr + ", NULL)", taskId);
    }
}

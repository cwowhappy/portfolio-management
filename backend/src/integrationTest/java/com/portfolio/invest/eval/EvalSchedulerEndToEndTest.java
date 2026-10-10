package com.portfolio.invest.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.application.eval.EvalScheduler;
import com.portfolio.invest.support.PostgresTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 调度→子进程→收割真链端到端（MS-30 Task 6 审查裁定必测，C1 修复的运行时验证）：
 * triggerNow 以生产同款命令起<b>真实 eval 子进程</b>（真 Boot jar、真 env 白名单、真 LLM、
 * 真 eval_schema 重置+迁移、真报告收割落库），轮询 eval_run 至终态断言全链走通。
 * 真跑约 8~10 分钟（题库 34 题），故按环境变量 opt-in——常规 suite 自动跳过：
 *
 * <pre>
 * cd backend && set -a && source ../.env && set +a && \
 *   EVAL_E2E=true ../gradlew --no-daemon evalBootJar integrationTest \
 *   --tests "com.portfolio.invest.eval.EvalSchedulerEndToEndTest" --console=plain
 * </pre>
 *
 * <p>子进程库经调度器 EVAL_DATASOURCE_* 注入指向本测试的共享容器（独立 schema 每轮重置，
 * 不碰 public 业务 schema）；@AfterAll 自清 eval_schema 与回流行（Task 3 共享容器教训：
 * 残留会让无 schema 限定的表计数测试双份失败、27 项快照断言被 EVAL_RUBRIC 行撑破）。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "EVAL_E2E", matches = "true")
class EvalSchedulerEndToEndTest extends PostgresTestSupport {

    /** 专属数据根（惰性创建——静态初始化在跳过路径也会执行，缺失资源不能在 load 期抛）。 */
    private static final java.util.function.Supplier<Path> DATA_ROOT = EvalSchedulerEndToEndTest::createDataRoot;

    /** evalBootJar 产物（惰性定位，须先 ./gradlew evalBootJar；缺失给引导性失败）。 */
    private static final java.util.function.Supplier<Path> EVAL_JAR = EvalSchedulerEndToEndTest::locateEvalJar;

    @Autowired
    EvalScheduler scheduler;
    @Autowired
    JdbcTemplate jdbc;

    @org.springframework.test.context.DynamicPropertySource
    static void evalProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("invest.eval.data-root", () -> DATA_ROOT.get().toString());
        registry.add("invest.eval.jar-path", () -> EVAL_JAR.get().toString());
        registry.add("invest.eval.timeout-minutes", () -> "45");
    }

    @Test
    @Timeout(1800)
    @DisplayName("真实端到端：triggerNow 起真子进程跑完题库，两轨 eval_run 各落终态行且收割链全走通")
    void givenRealEnvironment_whenTriggerNow_thenFullChainHarvested() throws Exception {
        long runId = scheduler.triggerNow("MANUAL");
        assertThat(runId).isPositive();

        Map<String, Object> row = awaitTerminalRow(runId, "AGENT");

        // 触发真值 + 终态非 RUNNING（真跑正常收尾应为 COMPLETED——收割链把报告吃进库表）
        assertThat(row.get("triggered_by")).isEqualTo("MANUAL");
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        // 数字口径：题库 34 题（real 轨 SKIPPED 除外），终态计数应基本铺满
        int terminal = ((Number) row.get("total_pass")).intValue()
                + ((Number) row.get("total_fail")).intValue();
        assertThat(terminal).isGreaterThanOrEqualTo(20);
        // 可比性指纹与版本快照（evalAssets 回流后 prompt_versions 应含 rubric.*）
        assertThat((String) row.get("question_bank_hash")).matches("[0-9a-f]{64}");
        assertThat((String) row.get("prompt_versions")).contains("rubric.");
        assertThat(row.get("duration_ms")).isNotNull();
        // 报告归档副本落盘（per-run 文件，非固定名原件）
        assertThat(Files.isRegularFile(Path.of((String) row.get("report_path")))).isTrue();
        // 回流行确实进了登记表（EVAL_RUBRIC 经报告回流，绕开生产 bootJar 无 rubric 资源）
        Integer rubricRows = jdbc.queryForObject(
                "SELECT count(*) FROM prompt_asset_version WHERE asset_type = 'EVAL_RUBRIC'", Integer.class);
        assertThat(rubricRows).isGreaterThanOrEqualTo(1);

        // 抽取轨（MS-30 跟进②）：对话轨收割后顺序接续 --track=extraction 子进程，另落一行
        // track=EXTRACT（不判回归：alert_status 恒 NONE，by_category 聚 EXTRACT 单类）
        Map<String, Object> extractionRow = awaitTerminalRow(runId, "EXTRACT");
        assertThat(extractionRow.get("track")).isEqualTo("EXTRACT");
        assertThat(extractionRow.get("alert_status")).isEqualTo("NONE");
        assertThat((String) extractionRow.get("by_category")).contains("EXTRACT");
        assertThat(Files.isRegularFile(Path.of((String) extractionRow.get("report_path")))).isTrue();
    }

    /** 轮询至终态（10s × 120 ≈ 20 分钟上限/轨；子进程逐题输出经 inheritIO 直达本测试 stdout）。 */
    private Map<String, Object> awaitTerminalRow(long agentRunId, String track) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            Thread.sleep(10_000);
            // 对话轨按主键直取；抽取轨行 id 不可知（看护内插），按同触发晚次 + track 定位；
            // 行可能尚未插（对话轨收割在先）——queryForList 空结果继续轮询
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, triggered_by, track, status, total_pass, total_fail, total_error, alert_status,
                           baseline_candidate, question_bank_hash, by_category::text AS by_category,
                           prompt_versions::text AS prompt_versions,
                           report_path, duration_ms
                      FROM eval_run
                     WHERE (? = 'AGENT' AND id = ?)
                        OR (? = 'EXTRACT' AND track = 'EXTRACT'
                            AND started_at >= (SELECT started_at FROM eval_run WHERE id = ?))
                     ORDER BY id LIMIT 1
                    """, track, agentRunId, track, agentRunId);
            if (rows.isEmpty()) {
                continue;
            }
            Map<String, Object> row = rows.get(0);
            System.out.printf("[e2e] %ds track=%s runId=%s status=%s pass=%s fail=%s error=%s%n",
                    (i + 1) * 10, track, row.get("id"), row.get("status"), row.get("total_pass"),
                    row.get("total_fail"), row.get("total_error"));
            if (!"RUNNING".equals(row.get("status"))) {
                return row;
            }
        }
        throw new AssertionError("eval " + track + " 轨 20 分钟未到终态（agentRunId=" + agentRunId + "）");
    }

    /** 共享容器自清（Task 3 教训）：eval_schema 残留 + 回流行 + 本跑 eval_run 行。 */
    @AfterAll
    void cleanSharedContainer() {
        jdbc.update("DROP SCHEMA IF EXISTS eval_schema CASCADE");
        jdbc.update("DELETE FROM prompt_asset_version WHERE asset_type = 'EVAL_RUBRIC'");
        jdbc.update("DELETE FROM eval_run");
    }

    private static Path createDataRoot() {
        try {
            return Files.createTempDirectory("ms30-eval-e2e");
        } catch (IOException e) {
            throw new IllegalStateException("e2e 数据根创建失败", e);
        }
    }

    private static Path locateEvalJar() {
        try (var candidates = Files.list(Path.of("build", "libs"))) {
            Path jar = candidates.filter(p -> p.getFileName().toString().endsWith("-eval.jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "未找到 build/libs/*-eval.jar——先执行 ./gradlew evalBootJar"));
            return jar.toAbsolutePath();
        } catch (IOException e) {
            throw new IllegalStateException("evalBootJar 产物定位失败", e);
        }
    }
}

package com.portfolio.invest.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.eval.EvalRunHarvest;
import com.portfolio.invest.domain.eval.EvalRunRepository;
import com.portfolio.invest.domain.eval.EvalRunRow;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * eval_run 落库契约（Testcontainers PG16 + V5）：RUNNING 行先插（triggered_by 真值 + 报告
 * 路径）、收割 update 全列（by_category/prompt_versions/verdict_reasons 三 JSONB 往返）、
 * baseline 唯一行定位与历史序上一跑查询（started_at DESC）、markFailed 兜底。
 *
 * <p>eval_run 仅由调度/收割写入（无其他测试触碰），@BeforeEach/@AfterEach 全表清空即可
 * 保持共享容器零残留（Task 3 类序教训）——不建 schema，无自清义务之外的动作。
 */
@SpringBootTest
class EvalRunRepositoryImplTest extends PostgresTestSupport {

    @Autowired
    EvalRunRepository repository;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanRuns() {
        jdbc.update("DELETE FROM eval_run");
    }

    @Test
    @DisplayName("给定触发，when插入 RUNNING 行，then返回自增 id 且 triggered_by/报告路径落真值")
    void givenTrigger_whenInsertRunning_thenRowCreatedWithTriggeredByAndReportPath() {
        long id = repository.insertRunning("SCHEDULED", "/data/eval/build/reports/eval-agent/eval-report.json");

        assertThat(repository.findLatestExcluding(id)).isEmpty(); // 库中仅本行，排除自身即空
        Map<String, Object> columns = jdbc.queryForMap(
                "SELECT triggered_by, status, report_path, finished_at FROM eval_run WHERE id = ?", id);
        assertThat(columns.get("triggered_by")).isEqualTo("SCHEDULED");
        assertThat(columns.get("status")).isEqualTo("RUNNING");
        assertThat(columns.get("report_path")).isEqualTo("/data/eval/build/reports/eval-agent/eval-report.json");
        assertThat(columns.get("finished_at")).isNull();
    }

    @Test
    @DisplayName("给定收割补丁，when更新，then全列落库且三 JSONB 列无损往返")
    void givenHarvestPatch_whenUpdateHarvested_thenAllColumnsRoundTrip() {
        long id = repository.insertRunning("MANUAL", "/tmp/eval-report.json");
        EvalRunHarvest patch = new EvalRunHarvest("PARTIAL", Instant.parse("2026-10-09T01:25:00Z"),
                14, 6, 6, Map.of("MARKET_FACT", new int[]{14, 0, 0}, "METRIC_CALC", new int[]{0, 0, 6}),
                Map.of("system.invest", 3, "rubric.answer-quality", 1), "qb-hash", "DEGRADED",
                false, List.of("总通过率 100.00%→77.78%（降 22.22pp ≥ 阈值 10pp）"),
                900_000L, "/tmp/eval-report-7.json");

        repository.updateHarvested(id, patch);

        EvalRunRow row = repository.findLatestExcluding(id + 100).orElseThrow();
        assertThat(row.id()).isEqualTo(id);
        assertThat(row.status()).isEqualTo("PARTIAL");
        assertThat(row.finishedAt()).isEqualTo(Instant.parse("2026-10-09T01:25:00Z"));
        assertThat(row.totalPass()).isEqualTo(14);
        assertThat(row.totalFail()).isEqualTo(6);
        assertThat(row.totalError()).isEqualTo(6);
        assertThat(row.byCategory().get("MARKET_FACT")).containsExactly(14, 0, 0);
        assertThat(row.byCategory().get("METRIC_CALC")).containsExactly(0, 0, 6);
        assertThat(row.promptVersions()).containsEntry("system.invest", 3)
                .containsEntry("rubric.answer-quality", 1);
        assertThat(row.questionBankHash()).isEqualTo("qb-hash");
        assertThat(row.alertStatus()).isEqualTo("DEGRADED");
        assertThat(row.baselineCandidate()).isFalse();
        assertThat(row.verdictReasons()).singleElement()
                .asString().contains("降 22.22pp");
        assertThat(row.durationMs()).isEqualTo(900_000L);
        assertThat(row.reportPath()).isEqualTo("/tmp/eval-report-7.json");
    }

    @Test
    @DisplayName("给定多跑历史与基准行，when查询，then findBaseline 唯一命中且上一跑按 started_at 倒序取最近")
    void givenRunsAndBaseline_whenQuery_thenBaselineUniqueAndPreviousLatest() {
        long first = repository.insertRunning("SCHEDULED", "/tmp/r1");
        long second = repository.insertRunning("SCHEDULED", "/tmp/r2");
        long current = repository.insertRunning("MANUAL", "/tmp/r3");
        // 首跑置为基准（模拟 Task 7 人工 PUT；唯一部分索引下先清后置）
        jdbc.update("UPDATE eval_run SET baseline = true WHERE id = ?", first);

        Optional<EvalRunRow> baseline = repository.findBaseline();
        assertThat(baseline).isPresent();
        assertThat(baseline.orElseThrow().id()).isEqualTo(first);
        assertThat(baseline.orElseThrow().baseline()).isTrue();

        Optional<EvalRunRow> previous = repository.findLatestExcluding(current);
        assertThat(previous).isPresent();
        assertThat(previous.orElseThrow().id()).isEqualTo(second);
    }

    @Test
    @DisplayName("给定收割兜底，when markFailed，then状态 FAILED 且理由清单留痕")
    void givenFailure_whenMarkFailed_thenStatusFailedWithReasons() {
        long id = repository.insertRunning("SCHEDULED", "/tmp/r1");

        repository.markFailed(id, List.of("评测报告缺失或不可解析: /tmp/r1"));

        // jsonb 列经仓库 RowMapper 读回（getString 通道），不走 queryForMap 的 PGobject 形态
        EvalRunRow row = repository.findLatestExcluding(id + 100).orElseThrow();
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.finishedAt()).isNotNull();
        assertThat(row.verdictReasons()).singleElement().asString().contains("评测报告缺失或不可解析");
    }
}

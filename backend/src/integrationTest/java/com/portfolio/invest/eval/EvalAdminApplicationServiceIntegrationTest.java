package com.portfolio.invest.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.application.eval.EvalAdminApplicationService;
import com.portfolio.invest.domain.eval.EvalErrorCode;
import com.portfolio.invest.domain.eval.EvalException;
import com.portfolio.invest.domain.eval.EvalRunRow;
import com.portfolio.invest.support.PostgresTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * eval admin 用例真库契约（MS-30 B5，Review Focus #5）：baseline 置位规则（PARTIAL/
 * FAILED/DEGRADED 拒 422 语义、COMPLETED+NONE 可置）与「先清旧后置新」事务序——序错则
 * 恒一基准部分唯一索引直接拒绝（本测试即失败）；运行历史倒序与 limit（RUNNING 行原样
 * 返回，停机残留清扫归部署文档不在此做）；版本行补注命中/缺失。@SpringBootTest 直调
 * 用例层（HTTP 映射归切片测试），@BeforeEach/@AfterEach 全表清空保持共享容器零残留。
 */
@SpringBootTest
class EvalAdminApplicationServiceIntegrationTest extends PostgresTestSupport {

    @Autowired
    EvalAdminApplicationService service;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanEvalTables() {
        jdbc.update("DELETE FROM eval_run");
        jdbc.update("DELETE FROM prompt_asset_version WHERE asset_key LIKE 'probe.%'");
    }

    /** 直插 eval_run 终态行（绕开调度链——本类只测 admin 查询/置位用例）。 */
    private long insertRun(String status, String alertStatus, Instant startedAt) {
        Long id = jdbc.queryForObject("""
                INSERT INTO eval_run (triggered_by, status, alert_status, started_at)
                VALUES ('SCHEDULED', ?, ?, ?) RETURNING id
                """, Long.class, status, alertStatus, Timestamp.from(startedAt));
        return id == null ? -1 : id;
    }

    private boolean baselineOf(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT baseline FROM eval_run WHERE id = ?", Boolean.class, id));
    }

    @Test
    @DisplayName("给定PARTIAL/FAILED/DEGRADED跑，when置基准，then拒ERR_BASELINE_INELIGIBLE且库不落")
    void givenPartialFailedDegradedRuns_whenSetBaselineTrue_thenRejectedWithCodeAndDbUntouched() {
        long partial = insertRun("PARTIAL", "NONE", Instant.now());
        long failed = insertRun("FAILED", "NONE", Instant.now());
        long degraded = insertRun("COMPLETED", "DEGRADED", Instant.now());

        for (long id : List.of(partial, failed, degraded)) {
            assertThatThrownBy(() -> service.setBaseline(id, true))
                    .isInstanceOf(EvalException.class)
                    .extracting(e -> ((EvalException) e).code())
                    .isEqualTo(EvalErrorCode.ERR_BASELINE_INELIGIBLE);
            assertThat(baselineOf(id)).as("run %s 不应被置位", id).isFalse();
        }
    }

    @Test
    @DisplayName("给定既有基准与合格新跑，when置基准，then旧行先清新行后置（唯一索引不炸）")
    void givenExistingBaselineAndEligibleRun_whenSetBaselineTrue_thenOldClearedNewSet() {
        long oldBaseline = insertRun("COMPLETED", "NONE", Instant.now().minusSeconds(7200));
        long newer = insertRun("COMPLETED", "RECOVERED", Instant.now());
        jdbc.update("UPDATE eval_run SET baseline = true WHERE id = ?", oldBaseline);

        service.setBaseline(newer, true);

        // 序错（先置后清）会撞 idx_eval_run_single_baseline 部分唯一索引——能通过即序正确
        assertThat(baselineOf(oldBaseline)).isFalse();
        assertThat(baselineOf(newer)).isTrue();
        Integer baselineCount = jdbc.queryForObject(
                "SELECT count(*) FROM eval_run WHERE baseline", Integer.class);
        assertThat(baselineCount).isEqualTo(1);
    }

    @Test
    @DisplayName("给定基准行，when置baseline=false，then仅该行清除且恒一约束保持")
    void givenBaselineRun_whenSetBaselineFalse_thenOnlyThatRowCleared() {
        long baseline = insertRun("COMPLETED", "NONE", Instant.now());
        jdbc.update("UPDATE eval_run SET baseline = true WHERE id = ?", baseline);

        service.setBaseline(baseline, false);

        assertThat(baselineOf(baseline)).isFalse();
        Integer baselineCount = jdbc.queryForObject(
                "SELECT count(*) FROM eval_run WHERE baseline", Integer.class);
        assertThat(baselineCount).isZero();
    }

    @Test
    @DisplayName("给定不存在的运行，when置基准，then抛EVAL_RUN_NOT_FOUND")
    void givenMissingRun_whenSetBaseline_thenNotFound() {
        assertThatThrownBy(() -> service.setBaseline(999_999L, true))
                .isInstanceOf(EvalException.class)
                .extracting(e -> ((EvalException) e).code())
                .isEqualTo(EvalErrorCode.EVAL_RUN_NOT_FOUND);
    }

    @Test
    @DisplayName("给定多跑历史含RUNNING行，when查历史，then倒序截断且RUNNING原样返回")
    void givenRunHistoryWithRunningRow_whenRecentRuns_thenDescendingLimitedWithRunningAsIs() {
        Instant now = Instant.now();
        long oldest = insertRun("COMPLETED", "NONE", now.minusSeconds(10800));
        long running = insertRun("RUNNING", "NONE", now.minusSeconds(3600));
        long newest = insertRun("PARTIAL", "DEGRADED", now.minusSeconds(1800));

        List<EvalRunRow> limited = service.recentRuns(2);

        assertThat(limited).extracting(EvalRunRow::id).containsExactly(newest, running);
        assertThat(limited.get(1).status()).isEqualTo("RUNNING"); // 停机残留原样返回（清扫归部署文档）
        assertThat(service.recentRuns(20)).extracting(EvalRunRow::id)
                .containsExactly(newest, running, oldest);
    }

    @Test
    @DisplayName("给定版本行，when补注，thennote落库且缺失行抛PROMPT_ASSET_NOT_FOUND")
    void givenVersionRow_whenUpdateAssetNote_thenNotePersistedAndMissingRejected() {
        Long id = jdbc.queryForObject("""
                INSERT INTO prompt_asset_version (asset_type, asset_key, version, content_hash)
                VALUES ('SKILL', 'probe.skill', 1, 'probe-hash') RETURNING id
                """, Long.class);

        service.updateAssetNote(id, "调整引语风格");

        String note = jdbc.queryForObject(
                "SELECT note FROM prompt_asset_version WHERE id = ?", String.class, id);
        assertThat(note).isEqualTo("调整引语风格");

        assertThatThrownBy(() -> service.updateAssetNote(999_999L, "x"))
                .isInstanceOf(EvalException.class)
                .extracting(e -> ((EvalException) e).code())
                .isEqualTo(EvalErrorCode.PROMPT_ASSET_NOT_FOUND);
    }
}

package com.portfolio.invest.domain.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 研报任务状态机（设计规格 §3.2；D5 时钟纪律：时间全入参零直调）。 */
class ReportTaskTest {

    private static final Instant CREATED = Instant.parse("2026-10-11T08:00:00Z");
    private static final Instant LATER = CREATED.plusSeconds(60);
    private static final Instant MUCH_LATER = CREATED.plusSeconds(900);

    // ---- create（默认态） ----

    @DisplayName("create：默认 PENDING/COLLECTING、tokenUsage=0、无错误信息、时间取入参")
    @Test
    void givenUserIdAndProject_whenCreate_thenPendingCollectingDefaults() {
        var task = ReportTask.create(7L, 3L, CREATED);
        assertThat(task.id()).isNull();
        assertThat(task.userId()).isEqualTo(7L);
        assertThat(task.projectId()).isEqualTo(3L);
        assertThat(task.status()).isEqualTo(ReportTask.Status.PENDING);
        assertThat(task.stage()).isEqualTo(ReportTask.Stage.COLLECTING);
        assertThat(task.tokenUsage()).isZero();
        assertThat(task.errorCode()).isNull();
        assertThat(task.errorMessage()).isNull();
        assertThat(task.createdAt()).isEqualTo(CREATED);
        assertThat(task.updatedAt()).isEqualTo(CREATED);
        assertThat(task.startedAt()).isNull();
        assertThat(task.finishedAt()).isNull();
    }

    @DisplayName("create：userId/projectId 缺失抛 USER_REQUIRED/PROJECT_REQUIRED")
    @Test
    void givenMissingIds_whenCreate_thenThrowRequired() {
        assertThatThrownBy(() -> ReportTask.create(null, 3L, CREATED))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.USER_REQUIRED));
        assertThatThrownBy(() -> ReportTask.create(7L, null, CREATED))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.PROJECT_REQUIRED));
    }

    // ---- markRunning ----

    @DisplayName("markRunning：PENDING→RUNNING 落 startedAt 与 updatedAt")
    @Test
    void givenPendingTask_whenMarkRunning_thenRunningWithStartedAt() {
        var task = ReportTask.create(7L, 3L, CREATED).markRunning(LATER);
        assertThat(task.status()).isEqualTo(ReportTask.Status.RUNNING);
        assertThat(task.stage()).isEqualTo(ReportTask.Stage.COLLECTING);
        assertThat(task.startedAt()).isEqualTo(LATER);
        assertThat(task.updatedAt()).isEqualTo(LATER);
        assertThat(task.finishedAt()).isNull();
    }

    @DisplayName("markRunning：非 PENDING 态（RUNNING/SUCCEEDED/FAILED）抛 TASK_INVALID_STATE")
    @Test
    void givenNonPendingTask_whenMarkRunning_thenThrowInvalidState() {
        var running = ReportTask.create(7L, 3L, CREATED).markRunning(LATER);
        var succeeded = running.succeed(100L, MUCH_LATER);
        var failed = failedTask("AGENT_ERROR", "boom");
        for (ReportTask bad : new ReportTask[]{running, succeeded, failed}) {
            assertThatThrownBy(() -> bad.markRunning(MUCH_LATER))
                    .isInstanceOfSatisfying(ResearchException.class,
                            e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.TASK_INVALID_STATE));
        }
    }

    // ---- markStage ----

    @DisplayName("markStage：RUNNING 内任意推进四阶段，updatedAt 刷新、状态不变")
    @Test
    void givenRunningTask_whenMarkStage_thenStageAdvancesWithinRunning() {
        var running = ReportTask.create(7L, 3L, CREATED).markRunning(LATER);
        var analyzing = running.markStage(ReportTask.Stage.ANALYZING, LATER.plusSeconds(10));
        assertThat(analyzing.status()).isEqualTo(ReportTask.Status.RUNNING);
        assertThat(analyzing.stage()).isEqualTo(ReportTask.Stage.ANALYZING);
        assertThat(analyzing.updatedAt()).isEqualTo(LATER.plusSeconds(10));
        // 回退亦合法（§3.2 回炉轮，D4 灵活流转语义）
        assertThat(analyzing.markStage(ReportTask.Stage.DRAFTING, LATER.plusSeconds(20)).stage())
                .isEqualTo(ReportTask.Stage.DRAFTING);
    }

    @DisplayName("markStage：同阶段幂等返回原实例（照 changeStage 同值语义）")
    @Test
    void givenSameStage_whenMarkStage_thenReturnSameInstance() {
        var running = ReportTask.create(7L, 3L, CREATED).markRunning(LATER);
        assertThat(running.markStage(ReportTask.Stage.COLLECTING, MUCH_LATER)).isSameAs(running);
    }

    @DisplayName("markStage：阶段缺失抛 STAGE_REQUIRED；非 RUNNING 态抛 TASK_INVALID_STATE")
    @Test
    void givenNullStageOrNonRunning_whenMarkStage_thenThrow() {
        var running = ReportTask.create(7L, 3L, CREATED).markRunning(LATER);
        assertThatThrownBy(() -> running.markStage(null, MUCH_LATER))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.STAGE_REQUIRED));
        var pending = ReportTask.create(7L, 3L, CREATED);
        assertThatThrownBy(() -> pending.markStage(ReportTask.Stage.ANALYZING, MUCH_LATER))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.TASK_INVALID_STATE));
        var failed = failedTask("TIMEOUT", "超时");
        assertThatThrownBy(() -> failed.markStage(ReportTask.Stage.ANALYZING, MUCH_LATER))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.TASK_INVALID_STATE));
    }

    // ---- succeed ----

    @DisplayName("succeed：RUNNING→SUCCEEDED 落 finishedAt 与最终 tokenUsage")
    @Test
    void givenRunningTask_whenSucceed_thenSucceededWithFinishedAtAndUsage() {
        var task = ReportTask.create(7L, 3L, CREATED).markRunning(LATER).succeed(12345L, MUCH_LATER);
        assertThat(task.status()).isEqualTo(ReportTask.Status.SUCCEEDED);
        assertThat(task.tokenUsage()).isEqualTo(12345L);
        assertThat(task.finishedAt()).isEqualTo(MUCH_LATER);
        assertThat(task.updatedAt()).isEqualTo(MUCH_LATER);
        assertThat(task.startedAt()).isEqualTo(LATER);
        assertThat(task.createdAt()).isEqualTo(CREATED);
        assertThat(task.errorCode()).isNull();
    }

    @DisplayName("succeed：非 RUNNING 态（PENDING/终态）抛 TASK_INVALID_STATE")
    @Test
    void givenNonRunningTask_whenSucceed_thenThrowInvalidState() {
        var pending = ReportTask.create(7L, 3L, CREATED);
        assertThatThrownBy(() -> pending.succeed(1L, MUCH_LATER))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.TASK_INVALID_STATE));
        var succeeded = ReportTask.create(7L, 3L, CREATED).markRunning(LATER).succeed(1L, MUCH_LATER);
        assertThatThrownBy(() -> succeeded.succeed(2L, MUCH_LATER))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.TASK_INVALID_STATE));
    }

    // ---- fail ----

    @DisplayName("fail：RUNNING→FAILED 带 errorCode/errorMessage/tokenUsage/finishedAt，startedAt 不变")
    @Test
    void givenRunningTask_whenFail_thenFailedCarriesErrorDiagnostics() {
        var task = ReportTask.create(7L, 3L, CREATED).markRunning(LATER)
                .fail("VALIDATION_FAILED", "回炉后仍未过门", 8888L, MUCH_LATER);
        assertThat(task.status()).isEqualTo(ReportTask.Status.FAILED);
        assertThat(task.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(task.errorMessage()).isEqualTo("回炉后仍未过门");
        assertThat(task.tokenUsage()).isEqualTo(8888L);
        assertThat(task.finishedAt()).isEqualTo(MUCH_LATER);
        assertThat(task.updatedAt()).isEqualTo(MUCH_LATER);
        assertThat(task.startedAt()).isEqualTo(LATER);
        assertThat(task.createdAt()).isEqualTo(CREATED);
    }

    @DisplayName("fail：任意态可失败（PENDING 未启动即失败 / FAILED 再失败覆盖诊断）")
    @Test
    void givenAnyState_whenFail_thenFailed() {
        // PENDING：池拒绝/恢复场景直接失败
        var fromPending = ReportTask.create(7L, 3L, CREATED).fail("AGENT_ERROR", "线程池拒绝", 0L, LATER);
        assertThat(fromPending.status()).isEqualTo(ReportTask.Status.FAILED);
        assertThat(fromPending.startedAt()).isNull();
        // FAILED：允许覆盖错误诊断（恢复 runner 对已失败任务重标）
        var refailed = fromPending.fail("AGENT_ERROR", "进程重启中断，可重试", 10L, MUCH_LATER);
        assertThat(refailed.status()).isEqualTo(ReportTask.Status.FAILED);
        assertThat(refailed.errorMessage()).isEqualTo("进程重启中断，可重试");
        assertThat(refailed.tokenUsage()).isEqualTo(10L);
    }

    // ---- reconstitute ----

    @DisplayName("reconstitute：全参还原持久化状态（不做校验）")
    @Test
    void givenAllFields_whenReconstitute_thenCarriedThrough() {
        var task = ReportTask.reconstitute(21L, 7L, 3L, ReportTask.Status.FAILED, ReportTask.Stage.VALIDATING,
                5555L, "TIMEOUT", "总超时", CREATED, MUCH_LATER, LATER, MUCH_LATER);
        assertThat(task.id()).isEqualTo(21L);
        assertThat(task.userId()).isEqualTo(7L);
        assertThat(task.projectId()).isEqualTo(3L);
        assertThat(task.status()).isEqualTo(ReportTask.Status.FAILED);
        assertThat(task.stage()).isEqualTo(ReportTask.Stage.VALIDATING);
        assertThat(task.tokenUsage()).isEqualTo(5555L);
        assertThat(task.errorCode()).isEqualTo("TIMEOUT");
        assertThat(task.errorMessage()).isEqualTo("总超时");
        assertThat(task.createdAt()).isEqualTo(CREATED);
        assertThat(task.updatedAt()).isEqualTo(MUCH_LATER);
        assertThat(task.startedAt()).isEqualTo(LATER);
        assertThat(task.finishedAt()).isEqualTo(MUCH_LATER);
    }

    @DisplayName("状态迁移不可变：wither 返回新实例，原实例状态不受影响")
    @Test
    void givenTransition_whenApplied_thenOriginalUnchanged() {
        var pending = ReportTask.create(7L, 3L, CREATED);
        pending.markRunning(LATER);
        assertThat(pending.status()).isEqualTo(ReportTask.Status.PENDING);
        assertThat(pending.updatedAt()).isEqualTo(CREATED);
    }

    private static ReportTask failedTask(String errorCode, String message) {
        return ReportTask.create(7L, 3L, CREATED).markRunning(LATER).fail(errorCode, message, 0L, MUCH_LATER);
    }
}

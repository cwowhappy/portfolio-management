package com.portfolio.invest.application.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.research.ReportTask;
import com.portfolio.invest.domain.research.ReportTaskRepository;
import com.portfolio.invest.domain.research.ResearchException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 启动恢复 Runner 单测（MS-32 B11，设计规格 §3.2）：findActiveAll 圈出的 PENDING/RUNNING
 * 全部标 FAILED(AGENT_ERROR,"进程重启中断，可重试")、tokenUsage 原值保留；无活跃任务时
 * 零写（幂等——恢复后 findActiveAll 必空）。
 */
class ReportTaskRecoveryRunnerTest {

    private static final Instant NOW = Instant.parse("2026-10-11T04:00:00Z");

    private final ReportTaskRepository repository = mock(ReportTaskRepository.class);
    private final ReportTaskRecoveryRunner runner = new ReportTaskRecoveryRunner(repository,
            Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

    private static ReportTask task(long id, ReportTask.Status status, long tokenUsage) {
        return ReportTask.reconstitute(id, 42L, 7L, status, ReportTask.Stage.ANALYZING,
                tokenUsage, null, null, NOW.minusSeconds(600), NOW.minusSeconds(300),
                NOW.minusSeconds(300), null);
    }

    @Test
    @DisplayName("给定 PENDING 与 RUNNING 各一，when 启动恢复，then两行均 FAILED(AGENT_ERROR) 且 tokenUsage 保留")
    void givenPendingAndRunningTasks_whenRun_thenBothFailedWithAgentError() {
        when(repository.findActiveAll()).thenReturn(List.of(
                task(1L, ReportTask.Status.PENDING, 0L),
                task(2L, ReportTask.Status.RUNNING, 1800L)));

        runner.run(null);

        ArgumentCaptor<ReportTask> captor = ArgumentCaptor.forClass(ReportTask.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(recovered -> {
            assertThat(recovered.status()).isEqualTo(ReportTask.Status.FAILED);
            assertThat(recovered.errorCode()).isEqualTo("AGENT_ERROR");
            assertThat(recovered.errorMessage()).contains("进程重启");
            assertThat(recovered.finishedAt()).isEqualTo(NOW);
        });
        assertThat(captor.getAllValues()).extracting(ReportTask::tokenUsage).containsExactly(0L, 1800L);
    }

    @Test
    @DisplayName("给定无活跃任务，when 启动恢复，then零写（幂等）")
    void givenNoActiveTasks_whenRun_thenNoWrites() {
        when(repository.findActiveAll()).thenReturn(List.of());

        runner.run(null);

        verify(repository, never()).save(any(ReportTask.class));
    }

    @Test
    @DisplayName("给定单行恢复失败抛异常，when 启动恢复，then异常向上透出（启动期可见，不静默吞）")
    void givenSaveBlowsUp_whenRun_thenPropagates() {
        when(repository.findActiveAll()).thenReturn(List.of(task(1L, ReportTask.Status.RUNNING, 5L)));
        when(repository.save(any(ReportTask.class))).thenThrow(new ResearchException("NOT_FOUND", "行已消失"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(ResearchException.class);
    }
}

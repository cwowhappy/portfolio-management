package com.portfolio.invest.application.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.eval.EvalRunRepository;
import com.portfolio.invest.domain.eval.PromptAssetVersionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 触发用例单测（MS-30 B5 审查 I2）：调度器 {@code IllegalStateException}（未启用/缺 key/
 * 派生或启动失败，文案面向运维）包成 {@link EvalTriggerUnavailableException} 透出原文案；
 * 互斥冲突 {@link EvalRunInProgressException} 原样穿透不包裹（409 语义不得被 503 吞掉）。
 * baseline/历史/补注路径归真库集成测试（EvalAdminApplicationServiceIntegrationTest）。
 */
class EvalAdminApplicationServiceTest {

    private final EvalScheduler scheduler = Mockito.mock(EvalScheduler.class);
    private final EvalAdminApplicationService service = new EvalAdminApplicationService(
            scheduler, Mockito.mock(EvalRunRepository.class),
            Mockito.mock(PromptAssetVersionRepository.class));

    @DisplayName("给定调度器前置失败，when手动触发，then包503异常并透出原文案")
    @Test
    void givenSchedulerIllegalState_whenTriggerManual_thenWrappedWithOriginalMessage() {
        when(scheduler.triggerNow("MANUAL")).thenThrow(
                new IllegalStateException("缺少 DEEPSEEK_API_KEY：评测子进程须真实 LLM，请在服务环境变量配置后重启"));

        assertThatThrownBy(() -> service.triggerManual())
                .isInstanceOf(EvalTriggerUnavailableException.class)
                .hasMessage("缺少 DEEPSEEK_API_KEY：评测子进程须真实 LLM，请在服务环境变量配置后重启")
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @DisplayName("给定进行中冲突，when手动触发，then409异常原样穿透不被包裹")
    @Test
    void givenRunInProgressConflict_whenTriggerManual_thenPassesThroughUnwrapped() {
        when(scheduler.triggerNow("MANUAL"))
                .thenThrow(new EvalRunInProgressException("评测运行进行中（手动与定时互斥），请稍后再试"));

        assertThatThrownBy(() -> service.triggerManual())
                .isInstanceOf(EvalRunInProgressException.class);
    }

    @DisplayName("给定调度器受理，when手动触发，then返回runId")
    @Test
    void givenSchedulerAccepted_whenTriggerManual_thenReturnsRunId() {
        when(scheduler.triggerNow("MANUAL")).thenReturn(7L);

        assertThat(service.triggerManual()).isEqualTo(7L);
    }
}

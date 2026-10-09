package com.portfolio.invest.application.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.portfolio.invest.application.alert.AlertNotifier;
import com.portfolio.invest.application.auth.MailSender;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.eval.EvalRunRepository;
import com.portfolio.invest.domain.eval.PromptAssetVersionRepository;
import com.portfolio.invest.infrastructure.eval.SystemParentEnvironment;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Eval_MODE 门控守护（MS-30 B4 审查 C1）：EvalScheduler/EvalHarvester 必须与
 * PromptVersionRegistrar/SchedulingConfig 同款类级 {@code @ConditionalOnExpression}——
 * eval 子进程上下文（--Eval_MODE=true）里 registrar 缺席，而收割器曾无条件 @Service 强依赖
 * 它导致子进程 APPLICATION FAILED TO START（每次定时/手动触发必夜夜 FAILED）。两态切片
 * 断言（ApplicationContextRunner，沿 SchedulingConfigConditionTest 形态）：缺省/false
 * 三 bean 在（生产行为不变），true 三 bean 全缺席（子进程可启动）。完整上下文级守护见
 * integrationTest 的 EvalModeContextGuardIntegrationTest。
 */
class EvalModeBeanGatingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(InvestProperties.class, InvestProperties::new)
            .withBean(EvalRunRepository.class, () -> mock(EvalRunRepository.class))
            .withBean(PromptAssetVersionRepository.class, () -> mock(PromptAssetVersionRepository.class))
            .withBean(AgentPromptAssetPort.class, () -> mock(AgentPromptAssetPort.class))
            .withBean(ClasspathSkillRepository.class, () -> mock(ClasspathSkillRepository.class))
            .withBean(AlertNotifier.class, () -> mock(AlertNotifier.class))
            .withBean(MailSender.class, () -> mock(MailSender.class))
            .withUserConfiguration(PromptVersionRegistrar.class, EvalScheduler.class,
                    EvalHarvester.class, SystemParentEnvironment.class);

    @DisplayName("Eval_MODE=true：调度器/收割器/登记器全缺席（子进程上下文可启动，C1 回归防线）")
    @Test
    void givenEvalModeTrue_whenContextRuns_thenSchedulerHarvesterRegistrarAbsent() {
        runner.withPropertyValues("Eval_MODE=true").run(context -> {
            assertThat(context).doesNotHaveBean(EvalScheduler.class);
            assertThat(context).doesNotHaveBean(EvalHarvester.class);
            assertThat(context).doesNotHaveBean(PromptVersionRegistrar.class);
        });
    }

    @DisplayName("Eval_MODE 缺省：三 bean 照常装配（生产上下文行为不变）")
    @Test
    void givenEvalModeAbsent_whenContextRuns_thenAllBeansPresent() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(EvalScheduler.class);
            assertThat(context).hasSingleBean(EvalHarvester.class);
            assertThat(context).hasSingleBean(PromptVersionRegistrar.class);
        });
    }

    @DisplayName("Eval_MODE=false 显式关开关：三 bean 照常装配")
    @Test
    void givenEvalModeFalse_whenContextRuns_thenAllBeansPresent() {
        runner.withPropertyValues("Eval_MODE=false").run(context -> {
            assertThat(context).hasSingleBean(EvalScheduler.class);
            assertThat(context).hasSingleBean(EvalHarvester.class);
        });
    }
}

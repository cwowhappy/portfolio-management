package com.portfolio.invest.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * 调度开关（MS-30 B2，设计规格 §2.3 副作用禁用双保险②）：eval 子进程以命令行参数
 * {@code --Eval_MODE=true} 触发 {@code SchedulingConfig} 整体不注册——{@code @EnableScheduling}
 * 不生效即无 {@link ScheduledAnnotationBeanPostProcessor}，全部 @Scheduled cron（原则预警/
 * 证伪扫描/情报抽取/清理等）在评测上下文静默缺席。两态切片断言（ApplicationContextRunner，
 * 不起完整上下文）：缺省/false 在（生产行为不变），true 不在（eval 副作用禁用）。
 */
class SchedulingConfigConditionTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(SchedulingConfig.class);

    @DisplayName("Eval_MODE=true：SchedulingConfig 与调度后处理器均不注册（eval 副作用禁用）")
    @Test
    void givenEvalModeTrue_whenContextRuns_thenSchedulingBeansAbsent() {
        runner.withPropertyValues("Eval_MODE=true").run(context -> {
            assertThat(context).doesNotHaveBean(SchedulingConfig.class);
            assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }

    @DisplayName("Eval_MODE 缺省：调度设施照常装配（生产行为不变）")
    @Test
    void givenEvalModeAbsent_whenContextRuns_thenSchedulingEnabled() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SchedulingConfig.class);
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }

    @DisplayName("Eval_MODE=false 显式关开关：调度设施照常装配")
    @Test
    void givenEvalModeFalse_whenContextRuns_thenSchedulingEnabled() {
        runner.withPropertyValues("Eval_MODE=false").run(context -> {
            assertThat(context).hasSingleBean(SchedulingConfig.class);
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }
}

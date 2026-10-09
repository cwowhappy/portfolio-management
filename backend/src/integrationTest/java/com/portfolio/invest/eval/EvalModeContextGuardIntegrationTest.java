package com.portfolio.invest.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.application.eval.EvalAdminApplicationService;
import com.portfolio.invest.application.eval.EvalHarvester;
import com.portfolio.invest.application.eval.EvalScheduler;
import com.portfolio.invest.application.eval.PromptVersionRegistrar;
import com.portfolio.invest.support.PostgresTestSupport;
import com.portfolio.invest.web.EvalTriggerController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * Eval_MODE 完整上下文守护（MS-30 B4 审查 C1）：以 @SpringBootTest(properties="Eval_MODE=true")
 * 复刻 eval 子进程的上下文形态（同一 InvestAgentApplication 全量组件扫描）——断言上下文
 * 启动成功且调度器/收割器/登记器/调度后处理器全缺席。C1 事故根因正是「收割器无条件 @Service
 * 强依赖门控缺席的登记器」——单元层门控切片（EvalModeBeanGatingTest）只看单类条件，本类
 * 兜住「任何无条件 bean 依赖 Eval_MODE 门控 bean」的全上下文回归（bean 装配失败即启动失败）。
 */
@SpringBootTest(properties = "Eval_MODE=true")
class EvalModeContextGuardIntegrationTest extends PostgresTestSupport {

    @Autowired
    ApplicationContext context;

    @DisplayName("Eval_MODE=true 完整上下文：启动成功且 eval 侧门控 bean 全缺席")
    @Test
    void givenEvalModeTrueFullContext_whenStarted_thenGatedBeansAbsent() {
        assertThat(context.getBeanNamesForType(EvalScheduler.class)).isEmpty();
        assertThat(context.getBeanNamesForType(EvalHarvester.class)).isEmpty();
        assertThat(context.getBeanNamesForType(PromptVersionRegistrar.class)).isEmpty();
        // B5：admin 触发链（用例 + web 面）同表达式门控——强依赖门控缺席的 EvalScheduler
        assertThat(context.getBeanNamesForType(EvalAdminApplicationService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(EvalTriggerController.class)).isEmpty();
        // SchedulingConfig 条件化（Task 3）同场景兜底：调度后处理器缺席
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }
}

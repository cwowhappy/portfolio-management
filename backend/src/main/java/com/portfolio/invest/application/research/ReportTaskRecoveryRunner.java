package com.portfolio.invest.application.research;

import com.portfolio.invest.domain.research.ReportTask;
import com.portfolio.invest.domain.research.ReportTaskRepository;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 研报任务启动恢复（MS-32 B11，设计规格 §3.2 进程重启恢复）：启动时将 status IN (PENDING,
 * RUNNING) 的任务批量标 {@code FAILED(AGENT_ERROR, "进程重启中断，可重试")}——D3 失败重来语义，
 * 用户重新发起新任务（新任务新会话）。tokenUsage 原值保留在行上（已耗 token 属实），
 * 不做配额结算（恢复路径无法区分真实消耗与编排器未及回写的中间态，结算口径只认编排器终态回调）。
 *
 * <p>幂等：恢复后 findActiveAll 必空，重复启动零写。异常向上透出（启动期可见，不静默吞）。</p>
 */
@Component
public class ReportTaskRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReportTaskRecoveryRunner.class);

    /** 恢复错误码（V8 error_code 列口径；agent 侧 ReportWorkflowException.AGENT_ERROR 同名，application 禁引 agent 类型）。 */
    static final String RECOVERY_ERROR_CODE = "AGENT_ERROR";

    /** 业务时区（事件/时间戳口径与 ReportApplicationService 一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ReportTaskRepository repository;
    private final Clock clock;

    /** 主构造器（@Autowired：存在测试专用重载构造器时需显式指定注入入口）。 */
    @Autowired
    public ReportTaskRecoveryRunner(ReportTaskRepository repository) {
        this(repository, Clock.system(ZONE));
    }

    /** 测试构造器：注入时钟（恢复时间戳确定性断言，沿 B4 双构造器先例）。 */
    ReportTaskRecoveryRunner(ReportTaskRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ReportTask> active = repository.findActiveAll();
        for (ReportTask task : active) {
            repository.save(task.fail(RECOVERY_ERROR_CODE, "进程重启中断，可重试",
                    task.tokenUsage(), clock.instant()));
        }
        if (!active.isEmpty()) {
            log.warn("研报任务启动恢复：{} 个 PENDING/RUNNING 悬挂任务已标 FAILED(AGENT_ERROR，进程重启中断，可重试)",
                    active.size());
        }
    }
}

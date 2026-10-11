package com.portfolio.invest.domain.research;

import java.util.List;
import java.util.Optional;

/**
 * 研报任务仓库端口（MS-32，设计规格 §3.2/§3.9）。
 * 「同项目至多一个活跃任务」（需求 §三 F01）由 V8 部分唯一索引 uq_report_task_active
 * 兜底——并发双发起时第二个 save 以约束违例事务内早抛，恰一成功。
 */
public interface ReportTaskRepository {

    /** 新建或回存；saveAndFlush 语义——uq_report_task_active 等约束违例在事务内尽早抛出。 */
    ReportTask save(ReportTask task);

    Optional<ReportTask> findById(Long id);

    /** 项目最新任务（ORDER BY id DESC LIMIT 1）：详情页任务态/终态诊断取数口。 */
    Optional<ReportTask> findLatestByProjectId(Long projectId);

    /** 启动恢复：status IN (PENDING,RUNNING) 全量（RUNNING 悬挂→FAILED、PENDING 重新提交，§3.9）。 */
    List<ReportTask> findActiveAll();
}

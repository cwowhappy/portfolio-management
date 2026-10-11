package com.portfolio.invest.domain.research;

import java.util.List;
import java.util.Optional;

/**
 * 研报仓库端口（MS-32，设计规格 §3.10 确认状态机）：任务成功后落定稿，
 * confirm/reject 的并发争用由持久层兜底（终态再操作 empty 转 409）。
 */
public interface ResearchReportRepository {

    /** 新建或回存；saveAndFlush 语义——约束违例在事务内尽早抛出。 */
    ResearchReport save(ResearchReport report);

    Optional<ResearchReport> findById(Long id);

    /** 项目研报列表，createdAt 倒序（最新定稿在前）。 */
    List<ResearchReport> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}

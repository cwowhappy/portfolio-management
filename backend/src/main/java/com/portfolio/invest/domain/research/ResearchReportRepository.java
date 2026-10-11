package com.portfolio.invest.domain.research;

import java.time.Instant;
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

    /**
     * 按任务取研报（MS-32 B11）：sink 幂等查重（Ruling-7——任务终态回调重放时已存在则
     * 整体 no-op，防崩溃重放双份研报）与 latestTask 的 reportId 回连共用。
     */
    Optional<ResearchReport> findByTaskId(Long taskId);

    /**
     * PENDING_CONFIRM→CONFIRMED 条件迁移（MS-32 B11，设计规格 §3.10 并发恰一——表无
     * version 列，裁定以状态行 {@code UPDATE ... WHERE status='PENDING_CONFIRM'} 争用判定）：
     * 命中返回 1；行不存在/已被并发迁移（非 PENDING）返回 0，调用方转 409。
     * {@code @Modifying} 原生更新，要求调用方处于活动事务中。
     */
    int confirmIfPending(Long id, Instant confirmedAt, Instant updatedAt);

    /**
     * PENDING_CONFIRM→REJECTED 条件迁移（reason 留痕）：语义同 {@link #confirmIfPending}
     * ——命中 1 / 未命中 0（并发恰一），活动事务由调用方提供。
     */
    int rejectIfPending(Long id, String reason, Instant updatedAt);
}

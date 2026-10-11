package com.portfolio.invest.application.research;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 研报任务落库端口（MS-32 B9 消费/B11 实现，设计规格 §3.2/§3.9）：编排器
 * {@code agent/report/ReportWorkflowOrchestrator} 在任务终态时的回调——application 侧定义、
 * 中性类型（禁引用 agent 类型，A13 分层）。
 *
 * <p><strong>回调契约（B9 编排器保证、B11 实现遵循）：</strong></p>
 * <ul>
 *   <li>每任务恰一次回调（成功/失败二选一），在编排器将任务行置终态（SUCCEEDED/FAILED）
 *       并写入最终 tokenUsage <strong>之后</strong>触发——实现方不得重复迁移任务行
 *       （{@code ReportTask.succeed/fail} 对非预期态抛 TASK_INVALID_STATE）；</li>
 *   <li>{@link #onTaskSucceeded}：研报（PENDING_CONFIRM）/锚点/假设行落库 + token 配额结算；
 *       contentMd 为校验门修正后定稿文本；{@code anchorIndex} 对应 anchors 列表下标
 *       （-1 不出现——门拦零绑定假设）；asOf 已由 agent 侧 best-effort 解析
 *       （失败 null，域 null 安全）；asOfKind 为 DATA/GENERATED/CALL（V8 列口径）；</li>
 *   <li>{@link #onTaskFailed}：仅 token 配额结算（错误诊断已由编排器落任务行）；</li>
 *   <li>实现须幂等（重复回调不重复结算，Ruling-7），异常向上抛由编排器记录（fail-closed：
 *       成功回调失败则任务行已被编排器改标 FAILED）。</li>
 * </ul>
 */
public interface ReportTaskSink {

    /** 锚点行（agent AnchorRecord 中性转录：state 三态 VERIFIED/SOURCED/UNVERIFIED）。 */
    record AnchorLine(String snippet, int occ, String state, String toolName,
                      Map<String, Object> args, Instant asOf, String asOfKind, String raw) {}

    /** 假设行（anchorIndex 对应 SucceededPayload.anchors 下标；门通过稿恒 ≥ 0）。 */
    record AssumptionLine(int seq, String statement, int anchorIndex) {}

    /** 成功产物：研报四字段 + 锚点/假设清单（落 research_report/anchor/assumption 三表）。 */
    record SucceededPayload(String title, String contentMd, String limitations,
                            List<AnchorLine> anchors, List<AssumptionLine> assumptions) {}

    /** 成功回调：任务行已 SUCCEEDED；payload 落库 + 配额结算（tokenUsage 与任务行一致）。 */
    void onTaskSucceeded(Long taskId, long tokenUsage, SucceededPayload payload);

    /** 失败回调：任务行已 FAILED（errorCode/errorMessage 已落行）；仅配额结算。 */
    void onTaskFailed(Long taskId, long tokenUsage, String errorCode, String errorMessage);
}

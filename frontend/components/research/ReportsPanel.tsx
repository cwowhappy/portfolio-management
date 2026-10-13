"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { getReport, latestTask, listReports, startReportTask } from "@/lib/reportApi";
import {
  REPORT_STAGE_LABELS,
  REPORT_STATUS_LABELS,
  type ReportDetailView,
  type ReportStatus,
  type ReportSummary,
  type ReportTaskStatus,
  type StartReportTaskResult,
} from "@/lib/reportSchemas";
import { useSaveAction } from "@/lib/useSaveAction";
import ReportDetail from "./ReportDetail";

// AI 研报面板（MS-32 F2，设计规格 §4.1 研究页第⑧区容器）：
// - 发起：window.confirm 二次确认（沿仓内删除确认先例）→ 202 → 受理提示 + 配额余量
//   提示条（quotaRemaining 唯一正常来源）+ 立即拉任务；402/409 映射固定行内文案；
// - 轮询：任务非终态（PENDING/RUNNING）5s setInterval latestTask（EvalRunsPanel 先例），
//   终态停、卸载清理；RUNNING→SUCCEEDED 收割时刷新列表并自动展开新研报；
// - 列表：后端保证 createdAt 倒序原样渲染；最新一张 PENDING_CONFIRM 默认展开；
// - latestTask 404「研报任务不存在」为初始态非错误（F1 Info-3），静默处理。

/** 402/409 → 固定行内文案（brief 裁定）；其余透传原文。 */
const friendlyStartError = (e: unknown): string => {
  const msg = e instanceof Error ? e.message : String(e);
  if (msg.includes("配额已用尽")) return "今日研报算力已用完，明日恢复";
  if (msg.includes("已有进行中的研报任务")) return "当前项目已有进行中的研报任务";
  return msg;
};

const statusChipCls: Record<ReportStatus, string> = {
  PENDING_CONFIRM: "border-[color:var(--color-line)] text-[color:var(--color-ink-dim)]",
  CONFIRMED: "border-[color:var(--color-up)]/50 text-[color:var(--color-up)]",
  REJECTED: "border-[color:var(--color-line)] text-[color:var(--color-ink-faint)]",
};

/** 任务态进度行：PENDING 排队 / RUNNING 阶段四态文案 / SUCCEEDED 完成 / FAILED 诊断透出。 */
function TaskProgress({ task }: { task: ReportTaskStatus }) {
  return (
    <div
      data-testid="report-task-progress"
      role="status"
      className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-[color:var(--color-ink-dim)]"
    >
      {task.status === "PENDING" && <span>排队中</span>}
      {task.status === "RUNNING" && (
        <span data-testid="report-task-stage">{REPORT_STAGE_LABELS[task.stage]}</span>
      )}
      {task.status === "SUCCEEDED" && <span>研报已生成</span>}
      {task.status === "FAILED" && (
        <span
          data-testid="report-task-failed"
          title={task.errorCode ?? undefined}
          className="text-[color:var(--color-down)]"
        >
          生成失败：{task.errorMessage ?? "未知错误"}
        </span>
      )}
      <span data-testid="report-task-usage" className="text-xs text-[color:var(--color-ink-faint)]">
        已耗 token {task.tokenUsage}
      </span>
    </div>
  );
}

export default function ReportsPanel({ projectId }: { projectId: number }) {
  const [task, setTask] = useState<ReportTaskStatus | null>(null);
  const [taskError, setTaskError] = useState<string | null>(null);
  const [reports, setReports] = useState<ReportSummary[] | null>(null);
  const [reportsError, setReportsError] = useState<string | null>(null);
  const [details, setDetails] = useState<Record<number, ReportDetailView>>({});
  // 错误带 id：只在与当前展开项匹配时展示（换展开项自动失配，无需在 effect 里同步清态）
  const [detailError, setDetailError] = useState<{ id: number; message: string } | null>(null);
  // undefined=初始未定（自动默认展开）/ number=展开 / null=用户全部收起
  const [openId, setOpenId] = useState<number | null | undefined>(undefined);
  const [quotaRemaining, setQuotaRemaining] = useState<number | null>(null);
  const [startNotice, setStartNotice] = useState<string | null>(null);
  const startSave = useSaveAction("发起研报失败");

  // 竞态守卫（轮询与手动刷新交错丢弃过期响应）+ 终态收割判定（观察到的上一态）
  const taskSeqRef = useRef(0);
  const lastStatusRef = useRef<string | null>(null);

  const loadReports = useCallback(() => {
    listReports(projectId)
      .then((rows) => {
        setReports(rows);
        setReportsError(null);
        // 默认展开最新一张待确认研报（仅初始未定时；用户操作后不再覆盖）
        setOpenId((cur) => {
          if (cur !== undefined) return cur;
          const pending = rows.find((r) => r.status === "PENDING_CONFIRM");
          return pending ? pending.id : null;
        });
      })
      .catch((e) => {
        setReportsError(e instanceof Error ? e.message : "加载研报列表失败");
      });
  }, [projectId]);

  const loadTask = useCallback(() => {
    const seq = ++taskSeqRef.current;
    latestTask(projectId)
      .then((next) => {
        if (seq !== taskSeqRef.current) return;
        const prev = lastStatusRef.current;
        lastStatusRef.current = next.status;
        setTask(next);
        setTaskError(null);
        // RUNNING/PENDING → SUCCEEDED 收割：刷新列表 + 自动展开新研报（挂载即终态不重复刷新）
        if (next.status === "SUCCEEDED" && (prev === "PENDING" || prev === "RUNNING")) {
          void loadReports();
          if (next.reportId != null) setOpenId(next.reportId);
        }
      })
      .catch((e) => {
        if (seq !== taskSeqRef.current) return;
        const msg = e instanceof Error ? e.message : "加载研报任务失败";
        if (msg.includes("研报任务不存在")) {
          // 无任务 = 初始态非错误（F1 report Info-3）
          lastStatusRef.current = null;
          setTask(null);
          setTaskError(null);
        } else {
          setTaskError(msg);
        }
      });
  }, [projectId, loadReports]);

  useEffect(() => {
    loadTask();
    loadReports();
  }, [loadTask, loadReports]);

  // 非终态（PENDING/RUNNING）5s 轮询；终态/卸载即清理（EvalRunsPanel.tsx:73 先例）
  const taskActive = task?.status === "PENDING" || task?.status === "RUNNING";
  useEffect(() => {
    if (!taskActive) return;
    const timer = setInterval(loadTask, 5000);
    return () => clearInterval(timer);
  }, [taskActive, loadTask]);

  const openDetail = openId == null ? undefined : details[openId];
  const openDetailError =
    detailError != null && detailError.id === openId ? detailError.message : null;
  useEffect(() => {
    if (openId == null || details[openId] != null) return; // 已缓存不重拉
    let alive = true;
    getReport(openId)
      .then((d) => {
        if (alive) setDetails((m) => ({ ...m, [d.id]: d }));
      })
      .catch((e) => {
        if (alive) {
          setDetailError({
            id: openId,
            message: e instanceof Error ? e.message : "加载研报详情失败",
          });
        }
      });
    return () => {
      alive = false;
    };
  }, [openId, details]);

  /** 确认/拒绝后绕过缓存重拉详情（状态即时刷新；失败保留旧详情，列表刷新已带回终态 chip）。 */
  const refreshDetail = useCallback((id: number) => {
    getReport(id)
      .then((d) => {
        setDetails((m) => ({ ...m, [d.id]: d }));
        setDetailError(null);
      })
      .catch(() => {});
  }, []);

  const handleConfirmed = useCallback(() => {
    void loadReports();
    if (openId != null) void refreshDetail(openId);
  }, [loadReports, refreshDetail, openId]);

  const startGenerate = () => {
    // 全站无通用确认弹窗组件（各域弹窗均为场景定制内联实现），P3 审查裁定沿用原生 confirm
    if (
      !window.confirm("确认发起深度研报生成？将消耗当日研报算力（token），完成后需人工确认定稿。")
    ) {
      return;
    }
    setStartNotice(null);
    void startSave.run(async () => {
      let accepted: StartReportTaskResult;
      try {
        accepted = await startReportTask(projectId);
      } catch (e) {
        throw new Error(friendlyStartError(e)); // run 统一落行内错误位
      }
      setQuotaRemaining(accepted.quotaRemaining);
      setStartNotice(`已受理研报任务 #${accepted.taskId}，生成完成后自动展示`);
      loadTask();
    });
  };

  return (
    <section
      data-testid="reports-panel"
      aria-label="AI 研报"
      className="space-y-4 rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="font-[family-name:var(--font-display)] text-[15px]">AI 研报</h2>
        <span className="text-xs text-[color:var(--color-ink-faint)]">
          深度研究生成 → 人工确认定稿
        </span>
        <button
          type="button"
          data-testid="report-generate-button"
          className="rounded-md bg-[color:var(--color-ink)] px-3 py-1.5 text-sm text-[color:var(--color-bg)] disabled:opacity-60"
          disabled={startSave.saving || taskActive}
          onClick={startGenerate}
        >
          {startSave.saving ? "发起中…" : taskActive ? "生成中…" : "生成研报"}
        </button>
      </div>

      {taskError && (
        <div data-testid="report-task-error" role="alert" className="text-sm text-[color:var(--color-down)]">
          {taskError}
        </div>
      )}
      {reportsError && (
        <div data-testid="report-list-error" role="alert" className="text-sm text-[color:var(--color-down)]">
          {reportsError}
        </div>
      )}
      {startSave.error && (
        <div data-testid="report-start-error" role="alert" className="text-sm text-[color:var(--color-down)]">
          {startSave.error}
        </div>
      )}
      {startNotice && (
        <p data-testid="report-start-notice" role="status" className="text-xs text-[color:var(--color-ink-dim)]">
          {startNotice}
        </p>
      )}
      {quotaRemaining != null && (
        <p data-testid="report-quota-hint" role="status" className="text-xs text-[color:var(--color-ink-faint)]">
          今日剩余研报算力 {quotaRemaining} token
        </p>
      )}
      {task && <TaskProgress task={task} />}

      {reports != null && reports.length === 0 && !reportsError && (
        <p
          data-testid="reports-empty"
          className="rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)] px-4 py-6 text-center text-[13px] text-[color:var(--color-ink-faint)]"
        >
          尚未生成研报——点击「生成研报」发起一次深度研究
        </p>
      )}

      {reports != null && reports.length > 0 && (
        <ul data-testid="report-list" className="space-y-2">
          {reports.map((r) => (
            <li
              key={r.id}
              data-testid={`report-item-${r.id}`}
              className="space-y-2 rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)]/70 px-4 py-3"
            >
              <div className="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  aria-expanded={openId === r.id}
                  aria-label={openId === r.id ? `收起研报「${r.title}」` : `展开研报「${r.title}」`}
                  onClick={() => setOpenId((cur) => (cur === r.id ? null : r.id))}
                  className="min-w-0 flex-1 truncate text-left text-sm text-[color:var(--color-ink)] hover:text-[color:var(--color-accent-strong)]"
                >
                  {r.title}
                </button>
                <span
                  className={`shrink-0 rounded border px-1.5 py-0.5 text-[11px] ${statusChipCls[r.status]}`}
                >
                  {REPORT_STATUS_LABELS[r.status]}
                </span>
                <span className="shrink-0 text-xs text-[color:var(--color-ink-faint)]">
                  #{r.id} · {r.createdAt.slice(0, 10)}
                </span>
              </div>
              {openId === r.id &&
                (openDetail ? (
                  <ReportDetail detail={openDetail} onConfirmed={handleConfirmed} />
                ) : openDetailError ? (
                  <div role="alert" className="text-sm text-[color:var(--color-down)]">
                    {openDetailError}
                  </div>
                ) : (
                  <p className="text-sm text-[color:var(--color-ink-faint)]">研报详情加载中…</p>
                ))}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

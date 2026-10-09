"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { adminApi, type EvalRun } from "@/lib/adminApi";
import { formatDurationMs, formatInstant } from "./format";

const ghostBtn =
  "rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-panel)] px-3 py-1.5 text-[12px] text-[color:var(--color-ink-dim)] transition-all enabled:hover:border-[color:var(--color-line)] enabled:hover:text-[color:var(--color-ink)] disabled:cursor-not-allowed disabled:opacity-40";

const primaryBtn =
  "rounded-md bg-[color:var(--color-up)] px-3 py-1.5 text-[12px] font-medium text-white transition-all enabled:hover:brightness-110 disabled:opacity-40";

const statusLabel: Record<EvalRun["status"], string> = {
  RUNNING: "进行中",
  COMPLETED: "完成",
  PARTIAL: "部分完成",
  FAILED: "失败",
};

const alertLabel: Record<EvalRun["alertStatus"], string> = {
  NONE: "正常",
  DEGRADED: "劣化",
  RECOVERED: "恢复",
};

const triggerLabel: Record<EvalRun["triggeredBy"], string> = {
  SCHEDULED: "定时",
  MANUAL: "手动",
};

/** 分类三元组 [pass, fail, error] → 悬停明细串。 */
function categoryTitle(run: EvalRun): string {
  return Object.entries(run.byCategory)
    .map(([cat, [pass, fail, err]]) => `${cat}：通过 ${pass} / 失败 ${fail} / 异常 ${err}`)
    .join("\n");
}

/**
 * 观测区块④b：评测运行历史——触发按钮（202 受理 / 409·503 文案透出）、
 * baseline 星标切换（422 资格文案透出）、最新行 RUNNING 时 5s 轮询（卸载清理）。
 */
export default function EvalRunsPanel() {
  const [runs, setRuns] = useState<EvalRun[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null);
  const [triggering, setTriggering] = useState(false);
  const [baselineBusyId, setBaselineBusyId] = useState<number | null>(null);
  const requestSeqRef = useRef(0); // 竞态守卫：轮询与手动刷新交错时丢弃过期响应

  const load = useCallback(() => {
    const seq = ++requestSeqRef.current;
    adminApi
      .listEvalRuns(20)
      .then((rows) => {
        if (seq !== requestSeqRef.current) return;
        setRuns(rows);
        setError(null);
      })
      .catch((e) => {
        if (seq !== requestSeqRef.current) return;
        setError(e instanceof Error ? e.message : "加载评测运行历史失败");
      });
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  // RUNNING 轮询：进行中 = 最新行 RUNNING（§2.5），收割落终态后自动停止
  const running = runs != null && runs[0]?.status === "RUNNING";
  useEffect(() => {
    if (!running) return;
    const timer = setInterval(load, 5000);
    return () => clearInterval(timer); // 卸载 / 离开 RUNNING 即清理
  }, [running, load]);

  async function trigger() {
    setTriggering(true);
    setNotice(null);
    try {
      const { runId } = await adminApi.triggerEvalRun(); // 202 受理；409/503 走 message 通道
      setNotice({ ok: true, text: `已受理评测运行 #${runId}，完成后自动刷新` });
      load();
    } catch (e) {
      setNotice({ ok: false, text: e instanceof Error ? e.message : "触发失败" });
    } finally {
      setTriggering(false);
    }
  }

  async function toggleBaseline(run: EvalRun) {
    setBaselineBusyId(run.id);
    setNotice(null);
    try {
      await adminApi.setEvalBaseline(run.id, !run.baseline); // 422 资格规则走 message 通道
      load();
    } catch (e) {
      setNotice({ ok: false, text: e instanceof Error ? e.message : "操作失败" });
    } finally {
      setBaselineBusyId(null);
    }
  }

  return (
    <div aria-label="评测运行历史">
      <div className="flex items-center justify-between">
        <h4 className="text-[13px] font-medium text-[color:var(--color-ink-dim)]">评测运行历史</h4>
        <button type="button" disabled={triggering} onClick={() => void trigger()} className={primaryBtn}>
          {triggering ? "触发中…" : "触发评测"}
        </button>
      </div>

      {error && (
        <p role="alert" className="mt-2 text-[12px] text-[color:var(--color-up)]">
          {error}
        </p>
      )}
      {notice && (
        <p
          data-testid="eval-trigger-notice"
          role="status"
          className={
            notice.ok
              ? "mt-2 text-[12px] text-[color:var(--color-down)]"
              : "mt-2 text-[12px] text-[color:var(--color-up)]"
          }
        >
          {notice.text}
        </p>
      )}

      {runs != null && runs.length === 0 && (
        <p
          data-testid="eval-runs-empty"
          className="mt-2 rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)] px-4 py-6 text-center text-[13px] text-[color:var(--color-ink-faint)]"
        >
          暂无评测运行：可手动触发或等待定时调度
        </p>
      )}

      {runs != null && runs.length > 0 && (
        <div className="mt-2 overflow-x-auto rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)]">
          <table className="w-full text-left text-[12px]">
            <thead>
              <tr className="border-b border-[color:var(--color-line)] font-[family-name:var(--font-mono)] text-[11px] tracking-wider text-[color:var(--color-ink-faint)]">
                <th className="px-3 py-2 font-normal">运行</th>
                <th className="px-3 py-2 font-normal">触发</th>
                <th className="px-3 py-2 font-normal">状态</th>
                <th className="px-3 py-2 font-normal">开始时间</th>
                <th className="px-3 py-2 font-normal">通过</th>
                <th className="px-3 py-2 font-normal">失败/异常</th>
                <th className="px-3 py-2 font-normal">告警</th>
                <th className="px-3 py-2 font-normal">时长</th>
                <th className="px-3 py-2 font-normal">基准</th>
              </tr>
            </thead>
            <tbody>
              {runs.map((run) => (
                <tr key={run.id} className="border-b border-[color:var(--color-line-soft)] last:border-b-0">
                  <td className="px-3 py-2 text-[color:var(--color-ink)]">#{run.id}</td>
                  <td className="px-3 py-2 text-[color:var(--color-ink-dim)]">{triggerLabel[run.triggeredBy]}</td>
                  <td className="px-3 py-2" title={run.verdictReasons.join("\n") || undefined}>
                    {statusLabel[run.status]}
                  </td>
                  <td className="whitespace-nowrap px-3 py-2 text-[color:var(--color-ink-dim)]">
                    {formatInstant(run.startedAt)}
                  </td>
                  <td
                    className="px-3 py-2 tabular-nums text-[color:var(--color-ink-dim)]"
                    title={categoryTitle(run) || undefined}
                  >
                    {run.totalPass}
                  </td>
                  <td className="px-3 py-2 tabular-nums text-[color:var(--color-ink-dim)]">
                    {run.totalFail}
                    {run.totalError > 0 && (
                      <span className="text-[color:var(--color-ink-faint)]"> / {run.totalError}</span>
                    )}
                  </td>
                  <td
                    className={
                      run.alertStatus === "DEGRADED"
                        ? "px-3 py-2 text-[color:var(--color-up)]"
                        : run.alertStatus === "RECOVERED"
                          ? "px-3 py-2 text-[color:var(--color-down)]"
                          : "px-3 py-2 text-[color:var(--color-ink-dim)]"
                    }
                  >
                    {alertLabel[run.alertStatus]}
                  </td>
                  <td className="whitespace-nowrap px-3 py-2 text-[color:var(--color-ink-dim)]">
                    {formatDurationMs(run.durationMs)}
                  </td>
                  <td className="px-3 py-2">
                    <div className="flex items-center gap-1.5">
                      <button
                        type="button"
                        disabled={baselineBusyId === run.id}
                        onClick={() => void toggleBaseline(run)}
                        aria-label={run.baseline ? `取消基准（运行 ${run.id}）` : `置为基准（运行 ${run.id}）`}
                        className={ghostBtn}
                      >
                        {run.baseline ? "★" : "☆"}
                      </button>
                      {run.baselineCandidate && !run.baseline && (
                        <span className="rounded border border-[color:var(--color-line)] px-1.5 py-px text-[10px] text-[color:var(--color-ink-faint)]">
                          恢复候选
                        </span>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

"use client";

import { useState } from "react";
import type { ConfirmAction } from "@/lib/reportApi";
import type { ReportStatus } from "@/lib/reportSchemas";
import { REPORT_STATUS_LABELS } from "@/lib/reportSchemas";
import { useSaveAction } from "@/lib/useSaveAction";

// 研报确认卡（设计规格 §3.10/§4.1）：仿 ChecklistConfirmCard 双态交互——确认定稿直接
// 提交；拒绝先展开理由框（后端 REJECT reason 必填，前端镜像拦截空理由）。防连点与错误
// 行内走 useSaveAction（in-flight ref 守卫连点只发一次）。终态（CONFIRMED/REJECTED）
// 只读展示 chip、无操作按钮（幂等由后端状态机保证，非待确认态 confirm 返回 409）。

export interface ReportConfirmCardProps {
  /** 研报当前状态：PENDING_CONFIRM 可操作，终态只读展示。 */
  status: ReportStatus;
  /** 提交：CONFIRM 定稿 / REJECT 拒绝（reason 必填由本卡拦截后透传）。 */
  onSubmit: (action: ConfirmAction, reason?: string) => Promise<void>;
}

export default function ReportConfirmCard({ status, onSubmit }: ReportConfirmCardProps) {
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState("");
  const save = useSaveAction("确认操作失败");

  const trimmed = reason.trim();

  if (status !== "PENDING_CONFIRM") {
    return (
      <div
        data-testid="report-confirm-card"
        className="flex flex-wrap items-center gap-2 rounded-xl border border-[color:var(--color-line-soft)] px-4 py-3"
      >
        <h4 className="text-sm font-medium text-[color:var(--color-ink)]">研报确认</h4>
        <span
          className={
            status === "CONFIRMED"
              ? "rounded border border-[color:var(--color-up)]/50 px-1.5 py-0.5 text-[11px] text-[color:var(--color-up)]"
              : "rounded border border-[color:var(--color-line)] px-1.5 py-0.5 text-[11px] text-[color:var(--color-ink-faint)]"
          }
        >
          {REPORT_STATUS_LABELS[status]}
        </span>
      </div>
    );
  }

  return (
    <div
      data-testid="report-confirm-card"
      className="space-y-3 rounded-xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)] p-4"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h4 className="text-sm font-medium text-[color:var(--color-ink)]">研报确认</h4>
        <span className="text-xs text-[color:var(--color-ink-dim)]">
          人工确认后定稿，定稿/拒绝均写入项目留痕
        </span>
      </div>

      {!rejecting ? (
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            className="rounded-md px-3 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)] disabled:opacity-60"
            disabled={save.saving}
            onClick={() => void save.run(() => onSubmit("CONFIRM", undefined))}
          >
            确认定稿
          </button>
          <button
            type="button"
            className="rounded-md border border-[color:var(--color-down)]/50 px-3 py-1.5 text-sm text-[color:var(--color-down)] disabled:opacity-60"
            disabled={save.saving}
            onClick={() => setRejecting(true)}
          >
            拒绝
          </button>
        </div>
      ) : (
        <div className="space-y-2">
          <label
            className="block text-xs text-[color:var(--color-ink-faint)]"
            htmlFor="report-reject-reason"
          >
            拒绝理由（必填）
          </label>
          <textarea
            id="report-reject-reason"
            aria-label="拒绝理由"
            className="min-h-16 w-full rounded-md border border-[color:var(--color-line)] px-2.5 py-1.5 text-sm"
            value={reason}
            placeholder="说明拒绝原因：如锚点数据已失效、结论不成立"
            onChange={(e) => setReason(e.target.value)}
          />
          {!trimmed && (
            <p className="text-xs text-[color:var(--color-down)]">拒绝研报须填写理由</p>
          )}
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              className="rounded-md border border-[color:var(--color-down)]/50 px-3 py-1.5 text-sm text-[color:var(--color-down)] disabled:opacity-60"
              disabled={!trimmed || save.saving}
              onClick={() => void save.run(() => onSubmit("REJECT", trimmed))}
            >
              提交（拒绝）
            </button>
            <button
              type="button"
              className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm text-[color:var(--color-ink-dim)] disabled:opacity-60"
              disabled={save.saving}
              onClick={() => setRejecting(false)}
            >
              返回
            </button>
          </div>
        </div>
      )}

      {save.error && <div className="text-sm text-[color:var(--color-down)]">{save.error}</div>}
      <p className="text-xs text-[color:var(--color-ink-faint)]">
        研报为研究参考，不构成投资建议；拒绝后可重新发起生成。
      </p>
    </div>
  );
}

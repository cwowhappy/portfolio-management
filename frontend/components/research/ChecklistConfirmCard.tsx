"use client";

import { useState } from "react";
import {
  CHECK_METRIC_LABELS,
  F01_MUST_ITEMS,
  type CheckItemResult,
} from "@/lib/researchSchemas";

// 纪律检查确认卡（D18 页面级确认流）：仿 InterruptApprovalCard 卡片壳 props 形态，
// 但不经 useInterrupt——检查单与工具审批机制解耦（ADR-0010 interrupt 绑对话内写工具，
// 不能复用于检查确认）。三态徽标：PASS 通过 / HIT 命中 / UNSET 中性——规则缺失显示
// 「未设定规则」，SELL/REDUCE 注入的证伪核对项（metric 为后端 falsifierLabel 自由文本）
// 显示「待核对」（人工核对语境，v1 不自动判定）。

export interface ChecklistConfirmCardProps {
  /** preview 产出的检查项列表（提交时原样回传留痕定格）。 */
  items: CheckItemResult[];
  /** 提交：CONFIRMED 逐项确认 / OVERRIDDEN 越过命中项（reason 必填，调用方提交留痕）。 */
  onSubmit: (result: "CONFIRMED" | "OVERRIDDEN", reason?: string) => Promise<void>;
}

/** metric 属规则/F01 已知名集 → 否则视为 SELL/REDUCE 注入的证伪核对项。 */
const isChecklistMetric = (metric: string) =>
  metric in CHECK_METRIC_LABELS || F01_MUST_ITEMS.includes(metric);

const badgeCls = (tone: "up" | "down" | "neutral") =>
  `rounded border px-1.5 py-0.5 text-[11px] ${
    tone === "up"
      ? "border-[color:var(--color-up)]/50 text-[color:var(--color-up)]"
      : tone === "down"
        ? "border-[color:var(--color-down)]/50 text-[color:var(--color-down)]"
        : "border-[color:var(--color-line)] text-[color:var(--color-ink-dim)]"
  }`;

function OutcomeBadge({ item }: { item: CheckItemResult }) {
  if (item.outcome === "PASS") return <span className={badgeCls("up")}>通过</span>;
  if (item.outcome === "HIT") return <span className={badgeCls("down")}>命中</span>;
  // UNSET 中性（F10）：规则缺失「未设定规则」；证伪核对项「待核对」
  return (
    <span className={badgeCls("neutral")}>
      {isChecklistMetric(item.metric) ? "未设定规则" : "待核对"}
    </span>
  );
}

export default function ChecklistConfirmCard({ items, onSubmit }: ChecklistConfirmCardProps) {
  const [overriding, setOverriding] = useState(false);
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const trimmed = reason.trim();
  const hitCount = items.filter((i) => i.outcome === "HIT").length;

  const doSubmit = async (result: "CONFIRMED" | "OVERRIDDEN", overrideReason?: string) => {
    setSubmitting(true);
    setError(null);
    try {
      await onSubmit(result, overrideReason);
    } catch (e) {
      setError(e instanceof Error ? e.message : "提交失败");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="rounded-xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)] p-4 space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <h4 className="text-sm font-medium text-[color:var(--color-ink)]">检查确认</h4>
        <span className="text-xs text-[color:var(--color-ink-dim)]">
          {hitCount > 0 ? `命中 ${hitCount} 项` : "无命中项"}
        </span>
      </div>
      <ul className="space-y-1.5">
        {items.map((item, i) => (
          <li key={i} className="flex flex-wrap items-center gap-2 text-sm">
            {!isChecklistMetric(item.metric) && (
              <span className="rounded border border-[color:var(--color-line)] px-1 py-0.5 text-[10px] text-[color:var(--color-ink-faint)]">
                证伪
              </span>
            )}
            <span className="min-w-0 flex-1 text-[color:var(--color-ink-dim)]">
              {CHECK_METRIC_LABELS[item.metric] ?? item.metric}
            </span>
            {item.threshold != null && item.currentValue != null && (
              <span className="text-xs text-[color:var(--color-ink-faint)]">
                当前 {item.currentValue} / 上限 {item.threshold}
              </span>
            )}
            <OutcomeBadge item={item} />
          </li>
        ))}
      </ul>

      {!overriding ? (
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            className="rounded-md px-3 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)] disabled:opacity-60"
            disabled={submitting}
            onClick={() => void doSubmit("CONFIRMED", undefined)}
          >
            逐项确认
          </button>
          <button
            type="button"
            className="rounded-md border border-[color:var(--color-down)]/50 px-3 py-1.5 text-sm text-[color:var(--color-down)] disabled:opacity-60"
            disabled={submitting}
            onClick={() => setOverriding(true)}
          >
            越过命中项继续
          </button>
        </div>
      ) : (
        <div className="space-y-2">
          <label
            className="block text-xs text-[color:var(--color-ink-faint)]"
            htmlFor="check-override-reason"
          >
            越过理由（必填）
          </label>
          <textarea
            id="check-override-reason"
            aria-label="越过理由"
            className="min-h-16 w-full rounded-md border border-[color:var(--color-line)] px-2.5 py-1.5 text-sm"
            value={reason}
            placeholder="留痕最低要求：说明越过命中项的理由"
            onChange={(e) => setReason(e.target.value)}
          />
          {!trimmed && (
            <p className="text-xs text-[color:var(--color-down)]">越过命中项须填写理由</p>
          )}
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              className="rounded-md border border-[color:var(--color-down)]/50 px-3 py-1.5 text-sm text-[color:var(--color-down)] disabled:opacity-60"
              disabled={!trimmed || submitting}
              onClick={() => void doSubmit("OVERRIDDEN", trimmed)}
            >
              提交（越过）
            </button>
            <button
              type="button"
              className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm text-[color:var(--color-ink-dim)] disabled:opacity-60"
              disabled={submitting}
              onClick={() => setOverriding(false)}
            >
              返回
            </button>
          </div>
        </div>
      )}

      {error && <div className="text-sm text-[color:var(--color-down)]">{error}</div>}
      <p className="text-xs text-[color:var(--color-ink-faint)]">
        检查为流程确认（软提醒，不阻断），不构成投资建议。
      </p>
    </div>
  );
}

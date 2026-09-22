"use client";

import { useState } from "react";
import {
  PRINCIPLE_METRIC_LABELS, RATIO_METRICS, createRule, deleteRule, updateRule,
} from "@/lib/wikiApi";
import type { PrincipleMetric, PrincipleRuleView } from "@/lib/types";

const METRICS: PrincipleMetric[] = [
  "SINGLE_POSITION_RATIO", "INDUSTRY_POSITION_RATIO", "STOCK_PE_MAX", "STOCK_PB_MAX",
];

/** 展示格式化：比例 → 百分比（去尾零），倍数原样。 */
export function formatThreshold(metric: PrincipleMetric, threshold: number): string {
  if (!RATIO_METRICS.has(metric)) return String(threshold);
  return `${Number((threshold * 100).toFixed(4))}%`;
}

export default function RulePanel({ rules, onChanged }: {
  rules: PrincipleRuleView[];
  onChanged: () => void;
}) {
  const [editing, setEditing] = useState<PrincipleRuleView | null>(null);
  const [metric, setMetric] = useState<PrincipleMetric>("SINGLE_POSITION_RATIO");
  const [threshold, setThreshold] = useState("20");
  const [description, setDescription] = useState("");
  const [error, setError] = useState<string | null>(null);
  const isRatio = RATIO_METRICS.has(metric);

  const startEdit = (r: PrincipleRuleView) => {
    setEditing(r);
    setMetric(r.metric);
    setThreshold(RATIO_METRICS.has(r.metric) ? String(Number((r.threshold * 100).toFixed(4))) : String(r.threshold));
    setDescription(r.description ?? "");
    setError(null);
  };

  const resetForm = () => {
    setEditing(null);
    setMetric("SINGLE_POSITION_RATIO");
    setThreshold("20");
    setDescription("");
    setError(null);
  };

  const save = async () => {
    setError(null);
    const num = Number(threshold);
    if (!(num > 0)) { setError("阈值需大于 0"); return; }
    if (isRatio && num > 100) { setError("比例阈值不能超过 100%"); return; }
    const payload = {
      metric,
      threshold: isRatio ? num / 100 : num,
      description: description.trim() || null,
    };
    try {
      if (editing) {
        const enabled = rules.find((r) => r.id === editing.id)?.enabled ?? editing.enabled;
        await updateRule(editing.id, { ...payload, enabled });
      } else {
        await createRule({ ...payload, enabled: true });
      }
      resetForm();
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "保存失败");
    }
  };

  const toggle = async (r: PrincipleRuleView) => {
    await updateRule(r.id, { metric: r.metric, threshold: r.threshold, enabled: !r.enabled, description: r.description });
    onChanged();
  };

  const remove = async (r: PrincipleRuleView) => {
    if (!confirm("删除该规则？")) return;
    await deleteRule(r.id);
    if (editing?.id === r.id) resetForm();
    onChanged();
  };

  return (
    <div className="space-y-6">
      <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-3">
        <div className="font-[family-name:var(--font-display)] text-[15px]">{editing ? "编辑规则" : "添加规则"}</div>
        <div className="flex flex-wrap gap-3 max-w-2xl">
          <select data-testid="wiki-rule-metric" aria-label="指标" disabled={!!editing}
            className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm disabled:opacity-60"
            value={metric} onChange={(e) => {
              setMetric(e.target.value as PrincipleMetric);
              setThreshold(RATIO_METRICS.has(e.target.value as PrincipleMetric) ? "20" : "40");
            }}>
            {METRICS.map((m) => (
              <option key={m} value={m} disabled={!editing && rules.some((r) => r.metric === m)}>
                {PRINCIPLE_METRIC_LABELS[m]}
              </option>
            ))}
          </select>
          <div className="flex items-center gap-1">
            <input data-testid="wiki-rule-threshold" aria-label="阈值" type="number" min="0" step={isRatio ? 1 : 0.1}
              className="w-28 rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm"
              value={threshold} onChange={(e) => setThreshold(e.target.value)} />
            <span className="text-sm text-[color:var(--color-ink-faint)]">{isRatio ? "%" : "倍"}</span>
          </div>
          <input data-testid="wiki-rule-description" className="flex-1 min-w-48 rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm"
            placeholder="说明（可选）" value={description} onChange={(e) => setDescription(e.target.value)} />
        </div>
        {error && <div className="text-sm text-[color:var(--color-down)]">{error}</div>}
        <div className="flex gap-2">
          <button data-testid="wiki-rule-save" type="button"
            className="rounded-md px-4 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]"
            onClick={save}>
            {editing ? "保存修改" : "添加规则"}
          </button>
          {editing && (
            <button type="button" className="rounded-md px-4 py-1.5 text-sm border border-[color:var(--color-line)]"
              onClick={resetForm}>
              取消
            </button>
          )}
        </div>
      </div>

      <div className="space-y-2" data-testid="wiki-rule-list">
        {rules.length === 0 && <div className="text-sm text-[color:var(--color-ink-faint)]">暂无规则</div>}
        {rules.map((r) => (
          <div key={r.id} data-testid={`wiki-rule-${r.id}`}
            className="flex items-center justify-between gap-2 rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-4">
            <div>
              <span className="font-medium">{PRINCIPLE_METRIC_LABELS[r.metric]}</span>
              <span className="ml-3 text-sm">≤ {formatThreshold(r.metric, r.threshold)}</span>
              {r.description && <span className="ml-3 text-sm text-[color:var(--color-ink-dim)]">{r.description}</span>}
            </div>
            <div className="flex shrink-0 items-center gap-3 text-xs">
              <button data-testid={`wiki-rule-toggle-${r.id}`} type="button"
                className={r.enabled
                  ? "rounded-md px-2 py-1 bg-[color:var(--color-ink)] text-[color:var(--color-bg)]"
                  : "rounded-md px-2 py-1 border border-[color:var(--color-line)] text-[color:var(--color-ink-dim)]"}
                onClick={() => toggle(r).catch(() => {})}>
                {r.enabled ? "已启用" : "已停用"}
              </button>
              <button data-testid={`wiki-rule-edit-${r.id}`} type="button"
                className="text-[color:var(--color-ink-dim)] hover:underline"
                onClick={() => startEdit(r)}>
                编辑
              </button>
              <button data-testid={`wiki-rule-delete-${r.id}`} type="button"
                className="text-[color:var(--color-ink-dim)] hover:underline"
                onClick={() => remove(r).catch(() => {})}>
                删除
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

"use client";

import { useState } from "react";
import {
  finalizeStrategy,
  reviseStrategy,
  saveFalsifiers,
  saveStrategyDraft,
  type SaveFalsifierItemInput,
} from "@/lib/researchApi";
import { useSaveAction } from "@/lib/useSaveAction";
import {
  FALSIFIER_KIND_LABELS,
  FALSIFIER_PREDICATE_LABELS,
  STRATEGY_STATE_LABELS,
  type FalsifierKind,
  type FalsifierPredicate,
  type FalsifierView,
  type StrategyView,
} from "@/lib/researchSchemas";

// 策略面板（D13 两级状态机 + D10 证伪条件集）：
// - 无文档 → 「创建草稿」以空六字段建稿（后端 draftOf）；
// - DRAFT → 六字段可编辑（字段不完整可暂存）+ 定稿显式动作（前端预校验估值下<上，后端 422 双保险）；
// - FINALIZED → 只读展示 + 修订（覆盖式回 DRAFT）。
// 表单预填随读模型 updatedAt 渲染期重置（照 IndustryStockTable 排序守卫写法）。

const PREDICATES: readonly FalsifierPredicate[] = ["PRICE_BELOW", "PRICE_ABOVE", "PE_ABOVE", "PB_ABOVE"];

/** 证伪条件编辑行：从读模型种子化，保存时映射回 PUT 整替项。 */
interface FalsifierRow {
  kind: FalsifierKind;
  predicate: FalsifierPredicate;
  threshold: string;
  note: string;
}

const toRow = (f: FalsifierView): FalsifierRow => ({
  kind: f.kind,
  predicate: f.predicate ?? "PRICE_BELOW",
  threshold: f.threshold == null ? "" : String(f.threshold),
  note: f.note ?? "",
});

const inputCls = "rounded-md border border-[color:var(--color-line)] px-2.5 py-1.5 text-sm";
const labelCls = "block text-xs text-[color:var(--color-ink-faint)] mb-1";
const btnPrimary =
  "rounded-md px-3 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)] disabled:opacity-60";
const btnGhost =
  "rounded-md px-3 py-1.5 text-sm border border-[color:var(--color-line)] text-[color:var(--color-ink-dim)] disabled:opacity-60";

/** 空串/非数 → null（DRAFT 态字段可空；定稿预校验另行拦截）。 */
const num = (v: string): number | null => {
  const t = v.trim();
  if (!t) return null;
  const n = Number(t);
  return Number.isFinite(n) ? n : null;
};

function ReadOnlyRow({ label, value }: { label: string; value: string | null }) {
  if (value == null || value === "") return null;
  return (
    <div className="mt-1.5 flex gap-2 text-sm leading-relaxed">
      <span className="w-20 shrink-0 text-xs text-[color:var(--color-ink-faint)]">{label}</span>
      <span className="min-w-0 flex-1 whitespace-pre-wrap break-words text-[color:var(--color-ink-dim)]">
        {value}
      </span>
    </div>
  );
}

export default function StrategyPanel({ projectId, strategy, falsifiers, onChanged }: {
  projectId: number;
  strategy: StrategyView | null;
  falsifiers: FalsifierView[];
  onChanged: () => void;
}) {
  const [thesis, setThesis] = useState(strategy?.thesis ?? "");
  const [low, setLow] = useState(strategy?.valuationLow == null ? "" : String(strategy.valuationLow));
  const [high, setHigh] = useState(strategy?.valuationHigh == null ? "" : String(strategy.valuationHigh));
  const [positionPlan, setPositionPlan] = useState(strategy?.positionPlan ?? "");
  const [buyConditions, setBuyConditions] = useState(strategy?.buyConditions ?? "");
  const [riskNotes, setRiskNotes] = useState(strategy?.riskNotes ?? "");
  const [rows, setRows] = useState<FalsifierRow[]>(falsifiers.map(toRow));
  const strategySave = useSaveAction("保存失败");
  const falsifierSave = useSaveAction("保存证伪条件失败");

  // 读模型变更（暂存/定稿/修订/手动标记触发父级重载）→ 渲染期重置表单
  const strategySeed = strategy?.updatedAt ?? "none";
  const [prevStrategySeed, setPrevStrategySeed] = useState(strategySeed);
  if (prevStrategySeed !== strategySeed) {
    setPrevStrategySeed(strategySeed);
    setThesis(strategy?.thesis ?? "");
    setLow(strategy?.valuationLow == null ? "" : String(strategy.valuationLow));
    setHigh(strategy?.valuationHigh == null ? "" : String(strategy.valuationHigh));
    setPositionPlan(strategy?.positionPlan ?? "");
    setBuyConditions(strategy?.buyConditions ?? "");
    setRiskNotes(strategy?.riskNotes ?? "");
  }
  // 整替保存后服务端回发新 id 集 → 重置编辑行
  const falsifierSeed = falsifiers.map((f) => f.id).join(",");
  const [prevFalsifierSeed, setPrevFalsifierSeed] = useState(falsifierSeed);
  if (prevFalsifierSeed !== falsifierSeed) {
    setPrevFalsifierSeed(falsifierSeed);
    setRows(falsifiers.map(toRow));
  }

  const draftBody = () => ({
    thesis: thesis.trim() || null,
    valuationLow: num(low),
    valuationHigh: num(high),
    positionPlan: positionPlan.trim() || null,
    buyConditions: buyConditions.trim() || null,
    riskNotes: riskNotes.trim() || null,
  });

  const saveDraft = () => {
    void strategySave.run(async () => {
      await saveStrategyDraft(projectId, draftBody());
      onChanged();
    });
  };

  const createDraft = () => {
    void strategySave.run(async () => {
      await saveStrategyDraft(projectId, {
        thesis: null, valuationLow: null, valuationHigh: null,
        positionPlan: null, buyConditions: null, riskNotes: null,
      });
      onChanged();
    });
  };

  const doFinalize = () => {
    const lo = num(low);
    const hi = num(high);
    if (lo == null || hi == null || lo >= hi) {
      strategySave.setError("估值下限必须小于上限，且均不能为空");
      return;
    }
    void strategySave.run(async () => {
      await finalizeStrategy(projectId);
      onChanged();
    });
  };

  const doRevise = () => {
    void strategySave.run(async () => {
      await reviseStrategy(projectId);
      onChanged();
    });
  };

  const updateRow = (i: number, patch: Partial<FalsifierRow>) => {
    setRows((rs) => rs.map((r, idx) => (idx === i ? { ...r, ...patch } : r)));
  };
  const addRow = (kind: FalsifierKind) => {
    setRows((rs) => [
      ...rs,
      { kind, predicate: "PRICE_BELOW", threshold: "", note: "" },
    ]);
  };

  const saveRows = () => {
    const items: SaveFalsifierItemInput[] = [];
    for (const r of rows) {
      if (r.kind === "PREDICATE") {
        const threshold = num(r.threshold);
        if (threshold == null || threshold < 0) {
          falsifierSave.setError("谓词阈值必须为非负数值");
          return;
        }
        items.push({ kind: "PREDICATE", predicate: r.predicate, threshold, note: r.note.trim() || undefined });
      } else if (!r.note.trim()) {
        falsifierSave.setError("事件类证伪条件必须填写说明");
        return;
      } else {
        items.push({ kind: "EVENT", note: r.note.trim() });
      }
    }
    void falsifierSave.run(async () => {
      await saveFalsifiers(projectId, items);
      onChanged();
    });
  };

  return (
    <section className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="font-[family-name:var(--font-display)] text-[15px]">投资策略</h2>
        {strategy && (
          <span
            className={`rounded px-1.5 py-0.5 text-[11px] ${
              strategy.state === "FINALIZED"
                ? "border border-[color:var(--color-up)]/50 text-[color:var(--color-up)]"
                : "border border-[color:var(--color-line)] text-[color:var(--color-ink-dim)]"
            }`}
          >
            {STRATEGY_STATE_LABELS[strategy.state]}
          </span>
        )}
      </div>

      {strategy == null && (
        <div className="space-y-3">
          <p className="text-sm text-[color:var(--color-ink-faint)]">
            尚未创建策略草稿——定稿前各字段可逐步补全暂存。
          </p>
          <button type="button" className={btnPrimary} disabled={strategySave.saving} onClick={createDraft}>
            创建草稿
          </button>
        </div>
      )}

      {strategy?.state === "DRAFT" && (
        <div className="space-y-3">
          <div>
            <label className={labelCls} htmlFor="strategy-thesis">投资逻辑</label>
            <textarea
              id="strategy-thesis"
              aria-label="投资逻辑"
              className={`${inputCls} min-h-16 w-full`}
              value={thesis}
              onChange={(e) => setThesis(e.target.value)}
            />
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <label className={labelCls} htmlFor="strategy-low">估值下限</label>
              <input
                id="strategy-low" aria-label="估值下限" type="number" step="any"
                className={`${inputCls} w-full`} value={low}
                onChange={(e) => setLow(e.target.value)}
              />
            </div>
            <div>
              <label className={labelCls} htmlFor="strategy-high">估值上限</label>
              <input
                id="strategy-high" aria-label="估值上限" type="number" step="any"
                className={`${inputCls} w-full`} value={high}
                onChange={(e) => setHigh(e.target.value)}
              />
            </div>
          </div>
          <div>
            <label className={labelCls} htmlFor="strategy-position">仓位计划</label>
            <textarea
              id="strategy-position" aria-label="仓位计划"
              className={`${inputCls} min-h-12 w-full`} value={positionPlan}
              onChange={(e) => setPositionPlan(e.target.value)}
            />
          </div>
          <div>
            <label className={labelCls} htmlFor="strategy-buy">买入条件</label>
            <textarea
              id="strategy-buy" aria-label="买入条件"
              className={`${inputCls} min-h-12 w-full`} value={buyConditions}
              onChange={(e) => setBuyConditions(e.target.value)}
            />
          </div>
          <div>
            <label className={labelCls} htmlFor="strategy-risk">风险提示</label>
            <textarea
              id="strategy-risk" aria-label="风险提示"
              className={`${inputCls} min-h-12 w-full`} value={riskNotes}
              onChange={(e) => setRiskNotes(e.target.value)}
            />
          </div>
          {strategySave.error && (
            <div className="text-sm text-[color:var(--color-down)]">{strategySave.error}</div>
          )}
          <div className="flex gap-2">
            <button type="button" className={btnPrimary} disabled={strategySave.saving} onClick={saveDraft}>
              暂存草稿
            </button>
            <button
              type="button"
              className="rounded-md border border-[color:var(--color-up)]/50 px-3 py-1.5 text-sm text-[color:var(--color-up)] disabled:opacity-60"
              disabled={strategySave.saving}
              onClick={doFinalize}
            >
              定稿
            </button>
          </div>
        </div>
      )}

      {strategy?.state === "FINALIZED" && (
        <div className="space-y-2">
          <ReadOnlyRow label="投资逻辑" value={strategy.thesis} />
          <ReadOnlyRow
            label="估值区间"
            value={
              strategy.valuationLow == null && strategy.valuationHigh == null
                ? null
                : `${strategy.valuationLow ?? "—"} ~ ${strategy.valuationHigh ?? "—"}`
            }
          />
          <ReadOnlyRow label="仓位计划" value={strategy.positionPlan} />
          <ReadOnlyRow label="买入条件" value={strategy.buyConditions} />
          <ReadOnlyRow label="风险提示" value={strategy.riskNotes} />
          {strategy.finalizedAt && (
            <p className="text-xs text-[color:var(--color-ink-faint)]">
              定稿于 {strategy.finalizedAt.slice(0, 10)}
            </p>
          )}
          {strategySave.error && (
            <div className="text-sm text-[color:var(--color-down)]">{strategySave.error}</div>
          )}
          {/* id=REVISE 评审提示链接的页内锚点（FalsifierReviewCard「去修订策略」） */}
          <button
            type="button"
            id="strategy-revise"
            className={btnGhost}
            disabled={strategySave.saving}
            onClick={doRevise}
          >
            修订（回草稿）
          </button>
        </div>
      )}

      {strategy != null && (
        <div className="space-y-3 border-t border-[color:var(--color-line-soft)] pt-4">
          <h3 className="text-sm font-medium text-[color:var(--color-ink)]">证伪条件</h3>
          <ul className="space-y-2">
            {rows.map((r, i) => (
              <li key={i} className="flex flex-wrap items-end gap-2">
                <span className="rounded border border-[color:var(--color-line)] px-1.5 py-0.5 text-[11px] text-[color:var(--color-ink-dim)]">
                  {FALSIFIER_KIND_LABELS[r.kind]}
                </span>
                {r.kind === "PREDICATE" ? (
                  <>
                    <div>
                      <label className={labelCls} htmlFor={`f-predicate-${i}`}>谓词</label>
                      <select
                        id={`f-predicate-${i}`} aria-label="谓词" className={inputCls}
                        value={r.predicate}
                        onChange={(e) => updateRow(i, { predicate: e.target.value as FalsifierPredicate })}
                      >
                        {PREDICATES.map((p) => (
                          <option key={p} value={p}>{FALSIFIER_PREDICATE_LABELS[p]}</option>
                        ))}
                      </select>
                    </div>
                    <div>
                      <label className={labelCls} htmlFor={`f-threshold-${i}`}>阈值</label>
                      <input
                        id={`f-threshold-${i}`} aria-label="阈值" type="number" step="any"
                        className={inputCls} value={r.threshold}
                        onChange={(e) => updateRow(i, { threshold: e.target.value })}
                      />
                    </div>
                    <div className="min-w-40 flex-1">
                      <label className={labelCls} htmlFor={`f-note-${i}`}>说明</label>
                      <input
                        id={`f-note-${i}`} aria-label="说明" className={`${inputCls} w-full`}
                        value={r.note} placeholder="说明（可选）"
                        onChange={(e) => updateRow(i, { note: e.target.value })}
                      />
                    </div>
                  </>
                ) : (
                  <div className="min-w-56 flex-1">
                    <label className={labelCls} htmlFor={`f-event-${i}`}>事件说明</label>
                    <input
                      id={`f-event-${i}`} aria-label="事件说明" className={`${inputCls} w-full`}
                      value={r.note} placeholder="事件说明（必填）"
                      onChange={(e) => updateRow(i, { note: e.target.value })}
                    />
                  </div>
                )}
                <button
                  type="button"
                  className={btnGhost}
                  aria-label={`删除条件 ${i + 1}`}
                  onClick={() => setRows((rs) => rs.filter((_, idx) => idx !== i))}
                >
                  删除
                </button>
              </li>
            ))}
          </ul>
          <div className="flex flex-wrap gap-2">
            <button type="button" className={btnGhost} onClick={() => addRow("PREDICATE")}>
              添加谓词条件
            </button>
            <button type="button" className={btnGhost} onClick={() => addRow("EVENT")}>
              添加事件条件
            </button>
            <button type="button" className={btnPrimary} disabled={falsifierSave.saving} onClick={saveRows}>
              保存证伪条件
            </button>
          </div>
          {falsifierSave.error && (
            <div className="text-sm text-[color:var(--color-down)]">{falsifierSave.error}</div>
          )}
        </div>
      )}
    </section>
  );
}

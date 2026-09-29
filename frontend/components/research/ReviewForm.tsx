"use client";

import { useEffect, useState } from "react";
import RefluxButton from "@/components/research/RefluxButton";
import { getChecks, updateReview } from "@/lib/researchApi";
import { useSaveAction } from "@/lib/useSaveAction";
import {
  REVIEW_FIELDS,
  REVIEW_TIER_LABELS,
  type CheckRecordView,
  type ReviewFieldDef,
  type ReviewView,
} from "@/lib/researchSchemas";

// 复盘修正表单（F13/F14）：字段清单常量表驱动（REVIEW_FIELDS——MS-24 收敛后只改常量不动组件）。
// auto 字段双列：快照自动值灰显只读（口径 tooltip=快照定格标注）+ 覆盖值可编辑（overrides JSONB）；
// 其余字段单列可编辑（answers JSONB）；「无数据」快照标注原样展示不以空白/0 兜底（Review Focus 1）。
// 快照创建时定格、修正不复算（F14）——表单只改 answers/overrides/narrative/tradeIds；
// trade_ids 归因圈选自动带入后可手动增删（D11，域侧去重排序收敛）。

const inputCls = "rounded-md border border-[color:var(--color-line)] px-2.5 py-1.5 text-sm";
const labelCls = "block text-xs text-[color:var(--color-ink-faint)] mb-1";
const btnGhost =
  "rounded-md px-3 py-1.5 text-sm border border-[color:var(--color-line)] text-[color:var(--color-ink-dim)] disabled:opacity-60";
const btnPrimary =
  "rounded-md px-3 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)] disabled:opacity-60";

/** JSONB 弹性值 → 表单字符串（null 容忍为空串）。 */
function stringRecord(v: Record<string, unknown> | null): Record<string, string> {
  if (!v) return {};
  const out: Record<string, string> = {};
  for (const [k, val] of Object.entries(v)) out[k] = val == null ? "" : String(val);
  return out;
}

export default function ReviewForm({
  projectId,
  review,
  onUpdated,
  onClose,
}: {
  projectId: number;
  review: ReviewView;
  /** 修正/回流回包：调用方更新读模型（列表行 + 当前选中）。 */
  onUpdated: (updated: ReviewView) => void;
  onClose: () => void;
}) {
  const fields = REVIEW_FIELDS[review.tier];
  const { snapshot } = review;
  const [answers, setAnswers] = useState<Record<string, string>>(() => stringRecord(review.answers));
  const [overrides, setOverrides] = useState<Record<string, string>>(() =>
    stringRecord(review.overrides),
  );
  const [narrative, setNarrative] = useState(review.narrative ?? "");
  const [tradeIds, setTradeIds] = useState<number[]>(review.tradeIds);
  const [tradeInput, setTradeInput] = useState("");
  const [checks, setChecks] = useState<CheckRecordView[] | null>(null);
  const save = useSaveAction("保存复盘失败");

  // 4.3 预填数据源：检查留痕列表（失败不阻断表单——提示缺省隐藏）
  useEffect(() => {
    getChecks(projectId)
      .then(setChecks)
      .catch(() => setChecks([]));
  }, [projectId]);

  // 切换复盘行时整体重置（同 id 的修正/回流回包不重置——回流不应冲掉未保存编辑）
  const [prevId, setPrevId] = useState(review.id);
  if (prevId !== review.id) {
    setPrevId(review.id);
    setAnswers(stringRecord(review.answers));
    setOverrides(stringRecord(review.overrides));
    setNarrative(review.narrative ?? "");
    setTradeIds(review.tradeIds);
  }

  // 口径 tooltip：快照定格的口径标注（自动计算与手算一致的验收说明）
  const caliberTitle = `净值口径：${snapshot.navBasis}；价格口径：${snapshot.priceBasis}；截点：${snapshot.asOf}`;

  /** auto 列文案：periodReturn 数值转百分比，「无数据」字符串原样（Review Focus 1）。 */
  const autoText = (f: ReviewFieldDef): string => {
    if (f.auto !== "periodReturn") return "无数据";
    const v = snapshot.periodReturn;
    return typeof v === "number" ? `区间收益 ${(v * 100).toFixed(2)}%` : v;
  };

  /**
   * 4.3 纪律遵守度预填：复盘区间内 OVERRIDDEN 留痕计数（createdAt 为 UTC 即时戳，
   * 取 UTC 日期与本地区间比较——提示口径的近似可接受）。
   */
  const overriddenCount =
    checks == null
      ? null
      : checks.filter((c) => {
          if (c.result !== "OVERRIDDEN") return false;
          const day = c.createdAt.slice(0, 10);
          return day >= review.periodStart && day <= review.periodEnd;
        }).length;

  /** 分桶收集（空值不入载荷）：answers=普通字段，overrides=auto 字段覆盖值。 */
  const collect = (overrideOnly: boolean): Record<string, string> => {
    const out: Record<string, string> = {};
    for (const f of fields) {
      if ((f.auto != null) !== overrideOnly) continue;
      const v = ((overrideOnly ? overrides : answers)[f.id] ?? "").trim();
      if (v) out[f.id] = v;
    }
    return out;
  };

  const addTrade = () => {
    const n = Number(tradeInput.trim());
    if (!Number.isInteger(n) || n <= 0) {
      save.setError("交易 ID 须为正整数");
      return;
    }
    save.setError(null);
    if (!tradeIds.includes(n)) setTradeIds((ids) => [...ids, n]);
    setTradeInput("");
  };

  const doSave = () => {
    void save.run(async () => {
      const updated = await updateReview(projectId, review.id, {
        answers: collect(false),
        overrides: collect(true),
        narrative: narrative.trim() || null,
        tradeIds,
      });
      onUpdated(updated);
    });
  };

  return (
    <div className="space-y-4 rounded-xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)] p-4">
      <div className="flex flex-wrap items-center gap-2">
        <h4 className="text-sm font-medium text-[color:var(--color-ink)]">
          {REVIEW_TIER_LABELS[review.tier]} · {review.periodStart} ~ {review.periodEnd}
        </h4>
        <span className="text-xs text-[color:var(--color-ink-faint)]">
          快照已定格（创建时写入，修正不复算）
        </span>
        <button type="button" className={`${btnGhost} ml-auto`} onClick={onClose}>
          收起
        </button>
      </div>

      <ul className="space-y-3">
        {fields.map((f) => (
          <li key={f.id} className="space-y-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs text-[color:var(--color-ink-faint)]">
                {f.id} {f.label}
              </span>
              {f.overrideHint && overriddenCount != null && (
                <span className="rounded border border-[color:var(--color-line)] px-1.5 py-0.5 text-[11px] text-[color:var(--color-ink-dim)]">
                  本期越过 {overriddenCount} 次
                </span>
              )}
              {f.auto && (
                <span
                  title={caliberTitle}
                  className="cursor-help rounded border border-[color:var(--color-line)] px-1.5 py-0.5 text-[11px] text-[color:var(--color-ink-faint)]"
                >
                  口径
                </span>
              )}
            </div>
            {f.auto ? (
              // 双列：快照自动值（灰显只读）vs 覆盖值（可编辑，写 overrides）
              <div className="grid gap-2 sm:grid-cols-2">
                <div className="rounded-md border border-[color:var(--color-line-soft)] bg-[color:var(--color-bg)] px-2.5 py-1.5 text-sm text-[color:var(--color-ink-faint)]">
                  {autoText(f)}
                </div>
                <input
                  aria-label={f.label}
                  className={`${inputCls} w-full`}
                  value={overrides[f.id] ?? ""}
                  placeholder="覆盖值（手算修正，可选）"
                  onChange={(e) => setOverrides((o) => ({ ...o, [f.id]: e.target.value }))}
                />
              </div>
            ) : f.type === "textarea" ? (
              <textarea
                aria-label={f.label}
                className={`${inputCls} min-h-12 w-full`}
                value={answers[f.id] ?? ""}
                onChange={(e) => setAnswers((a) => ({ ...a, [f.id]: e.target.value }))}
              />
            ) : f.type === "select" ? (
              <select
                aria-label={f.label}
                className={`${inputCls} w-full`}
                value={answers[f.id] ?? ""}
                onChange={(e) => setAnswers((a) => ({ ...a, [f.id]: e.target.value }))}
              >
                <option value="">未选择</option>
                {f.options.map((o) => (
                  <option key={o} value={o}>
                    {o}
                  </option>
                ))}
              </select>
            ) : (
              <input
                aria-label={f.label}
                className={`${inputCls} w-full`}
                value={answers[f.id] ?? ""}
                onChange={(e) => setAnswers((a) => ({ ...a, [f.id]: e.target.value }))}
              />
            )}
          </li>
        ))}
      </ul>

      <div className="space-y-1">
        <label className={labelCls} htmlFor="review-narrative">
          复盘叙述（回流 wiki 的正文）
        </label>
        <textarea
          id="review-narrative"
          aria-label="复盘叙述"
          className={`${inputCls} min-h-16 w-full`}
          value={narrative}
          onChange={(e) => setNarrative(e.target.value)}
        />
      </div>

      <div className="space-y-2">
        <span className="block text-xs text-[color:var(--color-ink-faint)]">
          归因交易圈选（自动圈选后可修正）
        </span>
        {tradeIds.length > 0 ? (
          <ul className="flex flex-wrap gap-1.5">
            {tradeIds.map((t) => (
              <li
                key={t}
                className="flex items-center gap-1 rounded border border-[color:var(--color-line)] px-2 py-0.5 text-xs text-[color:var(--color-ink-dim)]"
              >
                #{t}
                <button
                  type="button"
                  aria-label={`移除交易 ${t}`}
                  className="text-[color:var(--color-ink-faint)] hover:text-[color:var(--color-down)]"
                  onClick={() => setTradeIds((ids) => ids.filter((x) => x !== t))}
                >
                  ×
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-xs text-[color:var(--color-ink-faint)]">
            暂无圈内交易（快照无数据或已全部移除）。
          </p>
        )}
        <div className="flex items-center gap-2">
          <input
            aria-label="添加交易 ID"
            inputMode="numeric"
            className={`${inputCls} w-36`}
            value={tradeInput}
            placeholder="交易 ID"
            onChange={(e) => setTradeInput(e.target.value)}
          />
          <button type="button" className={btnGhost} onClick={addTrade}>
            添加
          </button>
        </div>
      </div>

      {save.error && <div className="text-sm text-[color:var(--color-down)]">{save.error}</div>}

      <div className="flex flex-wrap items-center gap-2 border-t border-[color:var(--color-line-soft)] pt-3">
        <button type="button" className={btnPrimary} disabled={save.saving} onClick={doSave}>
          保存修正
        </button>
        <RefluxButton projectId={projectId} review={review} onDone={onUpdated} />
      </div>
    </div>
  );
}

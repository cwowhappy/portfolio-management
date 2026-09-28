"use client";

import { useCallback, useEffect, useState } from "react";
import ReviewForm from "@/components/research/ReviewForm";
import { createReview, getReviews } from "@/lib/researchApi";
import { useSaveAction } from "@/lib/useSaveAction";
import {
  REFLUX_STATE_LABELS,
  REVIEW_TIER_LABELS,
  type ReviewTier,
  type ReviewView,
} from "@/lib/researchSchemas";

// 复盘面板（F13-F16）：历史复盘列表（periodStart 倒序，后端排序）+ 三档新建
// （D7：月主默认/季深度/周简版；创建即服务端定格快照，POST 后展开修正表单）；
// 修正/回流经 ReviewForm 内联承载，字段清单常量表驱动（REVIEW_FIELDS）。

const TIERS: readonly ReviewTier[] = ["MONTHLY", "QUARTERLY", "WEEKLY"];
const inputCls = "rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm";
const btnPrimary =
  "rounded-md px-3 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)] disabled:opacity-60";

export default function ReviewPanel({ projectId }: { projectId: number }) {
  const [reviews, setReviews] = useState<ReviewView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<ReviewView | null>(null);
  // 新建（三档选择 + 区间）
  const [tier, setTier] = useState<ReviewTier>("MONTHLY");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const create = useSaveAction("创建复盘失败");

  const load = useCallback(() => {
    getReviews(projectId)
      .then((rs) => {
        setReviews(rs);
        setError(null);
      })
      .catch((e) => setError(e instanceof Error ? e.message : "加载失败"));
  }, [projectId]);

  useEffect(() => {
    load();
  }, [load]);

  const doCreate = () => {
    if (!from || !to) {
      create.setError("复盘起止日期不能为空");
      return;
    }
    if (from > to) {
      create.setError("复盘起点不能晚于终点");
      return;
    }
    void create.run(async () => {
      const created = await createReview(projectId, { tier, periodStart: from, periodEnd: to });
      setSelected(created);
      load();
    });
  };

  /** 修正/回流回包：同步列表行与当前选中（表单按 id 种子化，不冲未保存编辑）。 */
  const onUpdated = (updated: ReviewView) => {
    setSelected(updated);
    setReviews((rs) => (rs ?? []).map((r) => (r.id === updated.id ? updated : r)));
  };

  return (
    <section className="space-y-4 rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="font-[family-name:var(--font-display)] text-[15px]">复盘</h2>
        <button
          type="button"
          className="rounded-md border border-[color:var(--color-line)] px-2.5 py-1 text-xs text-[color:var(--color-ink-dim)] disabled:opacity-60"
          onClick={load}
        >
          刷新
        </button>
      </div>

      {error && <div className="text-sm text-[color:var(--color-down)]">{error}</div>}

      <div className="flex flex-wrap items-end gap-2">
        <div>
          <label className="block text-xs text-[color:var(--color-ink-faint)] mb-1" htmlFor="review-tier">
            复盘档位
          </label>
          <select
            id="review-tier"
            aria-label="复盘档位"
            className={inputCls}
            value={tier}
            onChange={(e) => setTier(e.target.value as ReviewTier)}
          >
            {TIERS.map((t) => (
              <option key={t} value={t}>
                {REVIEW_TIER_LABELS[t]}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="block text-xs text-[color:var(--color-ink-faint)] mb-1" htmlFor="review-from">
            复盘起始日
          </label>
          <input
            id="review-from"
            aria-label="复盘起始日"
            type="date"
            className={inputCls}
            value={from}
            onChange={(e) => setFrom(e.target.value)}
          />
        </div>
        <div>
          <label className="block text-xs text-[color:var(--color-ink-faint)] mb-1" htmlFor="review-to">
            复盘截止日
          </label>
          <input
            id="review-to"
            aria-label="复盘截止日"
            type="date"
            className={inputCls}
            value={to}
            onChange={(e) => setTo(e.target.value)}
          />
        </div>
        <button type="button" className={btnPrimary} disabled={create.saving} onClick={doCreate}>
          新建复盘
        </button>
        <span className="text-xs text-[color:var(--color-ink-faint)]">创建即定格快照（不复算历史）。</span>
      </div>
      {create.error && <div className="text-sm text-[color:var(--color-down)]">{create.error}</div>}

      {reviews == null || reviews.length === 0 ? (
        <p className="text-sm text-[color:var(--color-ink-faint)]">暂无复盘——选择档位与区间新建。</p>
      ) : (
        <ul className="space-y-1.5" data-testid="review-list">
          {reviews.map((r) => (
            <li key={r.id} className="flex flex-wrap items-center gap-2 text-sm">
              <button
                type="button"
                className="rounded-md border border-[color:var(--color-line)] px-2.5 py-1 text-sm text-[color:var(--color-ink-dim)] hover:border-[color:var(--color-ink-dim)]"
                onClick={() => setSelected(r)}
              >
                {REVIEW_TIER_LABELS[r.tier]} {r.periodStart} ~ {r.periodEnd}
              </button>
              <span
                className={`rounded border px-1.5 py-0.5 text-[11px] ${
                  r.refluxState === "REFLOWN"
                    ? "border-[color:var(--color-up)]/50 text-[color:var(--color-up)]"
                    : "border-[color:var(--color-line)] text-[color:var(--color-ink-dim)]"
                }`}
              >
                {REFLUX_STATE_LABELS[r.refluxState]}
              </span>
            </li>
          ))}
        </ul>
      )}

      {selected && (
        <ReviewForm
          projectId={projectId}
          review={selected}
          onUpdated={onUpdated}
          onClose={() => setSelected(null)}
        />
      )}
    </section>
  );
}

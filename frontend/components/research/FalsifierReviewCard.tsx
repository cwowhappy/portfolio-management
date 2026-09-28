"use client";

import { useState } from "react";
import { postFalsifierReview } from "@/lib/researchApi";
import { useSaveAction } from "@/lib/useSaveAction";
import {
  REVIEW_CONCLUSION_LABELS,
  type FalsifierHitView,
  type FalsifierReviewView,
  type ReviewConclusion,
} from "@/lib/researchSchemas";

// 证伪评审卡（F15）：四结论单选 + 理由必填（append-only 留痕，无修改入口）；
// 可关联历史命中行（hitId 软引用，服务端回填 review_id——首评占据、不覆盖）。
// REVISE 响应 suggestStrategyRevise=true → 提示链接锚到策略面板修订按钮
// （#strategy-revise）：落库与策略修订分离，不自动改（Review Focus 3）。

const CONCLUSIONS: readonly ReviewConclusion[] = ["HOLD", "REDUCE", "EXIT", "REVISE"];

export default function FalsifierReviewCard({
  projectId,
  hits,
  onSubmitted,
}: {
  projectId: number;
  /** 历史命中行候选（有落库 id 才可关联；实时行不参与）。 */
  hits: FalsifierHitView[];
  /** 提交成功：回传服务端回包（调用方更新留痕列表与命中行标签）。 */
  onSubmitted: (view: FalsifierReviewView) => void;
}) {
  const linkable = hits.filter((h) => h.id != null);
  const [hitId, setHitId] = useState("");
  const [conclusion, setConclusion] = useState<ReviewConclusion | "">("");
  const [reason, setReason] = useState("");
  const [result, setResult] = useState<FalsifierReviewView | null>(null);
  const save = useSaveAction("提交评审失败");

  const trimmed = reason.trim();
  const ready = conclusion !== "" && !!trimmed;

  const doSubmit = () => {
    if (!ready) return;
    const picked = conclusion as ReviewConclusion;
    void save.run(async () => {
      const view = await postFalsifierReview(projectId, {
        conclusion: picked,
        reason: trimmed,
        hitId: hitId ? Number(hitId) : undefined,
      });
      setResult(view);
      onSubmitted(view);
      // 表单复位（append-only 可连评多条，结果块保留最近一次）
      setHitId("");
      setConclusion("");
      setReason("");
    });
  };

  return (
    <div className="space-y-3 rounded-xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)] p-4">
      <h4 className="text-sm font-medium text-[color:var(--color-ink)]">证伪评审</h4>

      {linkable.length > 0 && (
        <div>
          <label
            className="block text-xs text-[color:var(--color-ink-faint)] mb-1"
            htmlFor="falsifier-review-hit"
          >
            关联命中行（可选）
          </label>
          <select
            id="falsifier-review-hit"
            aria-label="关联命中行"
            className="rounded-md border border-[color:var(--color-line)] px-2.5 py-1.5 text-sm"
            value={hitId}
            onChange={(e) => setHitId(e.target.value)}
          >
            <option value="">不关联</option>
            {linkable.map((h) => (
              <option key={h.id} value={String(h.id)}>
                命中 #{h.id}（{h.hitAt != null ? h.hitAt.slice(0, 10) : "时间未知"}）
              </option>
            ))}
          </select>
        </div>
      )}

      <fieldset>
        <legend className="block text-xs text-[color:var(--color-ink-faint)] mb-1">
          处置结论（必选）
        </legend>
        <div className="flex flex-wrap gap-3">
          {CONCLUSIONS.map((c) => (
            <label
              key={c}
              className="flex items-center gap-1.5 text-sm text-[color:var(--color-ink-dim)]"
            >
              <input
                type="radio"
                name="falsifier-conclusion"
                aria-label={REVIEW_CONCLUSION_LABELS[c]}
                checked={conclusion === c}
                onChange={() => setConclusion(c)}
              />
              {REVIEW_CONCLUSION_LABELS[c]}
            </label>
          ))}
        </div>
      </fieldset>

      <div className="space-y-1">
        <label
          className="block text-xs text-[color:var(--color-ink-faint)]"
          htmlFor="falsifier-review-reason"
        >
          评审理由（必填，留痕）
        </label>
        <textarea
          id="falsifier-review-reason"
          aria-label="评审理由"
          className="min-h-14 w-full rounded-md border border-[color:var(--color-line)] px-2.5 py-1.5 text-sm"
          value={reason}
          placeholder="为什么得出该结论（≤1000 字）"
          onChange={(e) => setReason(e.target.value)}
        />
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          className="rounded-md bg-[color:var(--color-ink)] px-3 py-1.5 text-sm text-[color:var(--color-bg)] disabled:opacity-60"
          disabled={!ready || save.saving}
          onClick={doSubmit}
        >
          提交评审
        </button>
        {conclusion === "" && (
          <span className="text-xs text-[color:var(--color-ink-faint)]">请先选择处置结论</span>
        )}
        {conclusion !== "" && !trimmed && (
          <span className="text-xs text-[color:var(--color-ink-faint)]">评审理由必填</span>
        )}
      </div>

      {save.error && <div className="text-sm text-[color:var(--color-down)]">{save.error}</div>}

      {result && (
        <div
          className="space-y-1 border-t border-[color:var(--color-line-soft)] pt-3"
          data-testid="falsifier-review-result"
        >
          <p className="text-xs text-[color:var(--color-ink-dim)]">
            评审已留痕：{REVIEW_CONCLUSION_LABELS[result.conclusion]}（#{result.id}）
          </p>
          {result.suggestStrategyRevise && (
            <p className="text-xs text-[color:var(--color-ink-dim)]">
              建议修订策略（不自动改，落库与修订分离）：
              <a
                href="#strategy-revise"
                className="text-[color:var(--color-accent)] hover:underline"
              >
                去修订策略
              </a>
            </p>
          )}
        </div>
      )}
    </div>
  );
}

"use client";

import { useState } from "react";
import { refluxReview } from "@/lib/researchApi";
import { useSaveAction } from "@/lib/useSaveAction";
import type { ReviewView } from "@/lib/researchSchemas";

// 回流按钮（F16 用户确认后入库，不自动回流）：确认弹层明示「将写入知识库 RESEARCH_NOTE」
// （category=SOP_REVIEW 的复盘叙述条目）；REFLOWN 幂等终态直接显示既有 wiki 条目号
// （Review Focus 4：二次点击不重复建条目）；wiki 写异常 → 后端 502 降级文案行内展示，
// 确认层保留可重试（reflux_state 仍 PENDING，复盘本体不受影响）。

export default function RefluxButton({
  projectId,
  review,
  onDone,
}: {
  projectId: number;
  review: ReviewView;
  /** 回流成功：回传服务端回包（REFLOWN + wikiEntryId），调用方更新读模型。 */
  onDone: (updated: ReviewView) => void;
}) {
  const [confirming, setConfirming] = useState(false);
  const save = useSaveAction("回流失败，请稍后重试");

  // 幂等终态：不再渲染动作按钮，只显示既有条目号
  if (review.refluxState === "REFLOWN") {
    return (
      <span className="text-xs text-[color:var(--color-ink-dim)]">
        已回流{review.wikiEntryId != null ? ` · wiki #${review.wikiEntryId}` : ""}
      </span>
    );
  }

  const doReflux = () => {
    void save.run(async () => {
      const updated = await refluxReview(projectId, review.id);
      setConfirming(false);
      onDone(updated);
    });
  };

  return (
    <div className="flex flex-wrap items-center gap-2">
      {confirming ? (
        <div
          className="flex flex-wrap items-center gap-2 rounded-md border border-[color:var(--color-line)] px-3 py-2"
          data-testid="reflux-confirm"
        >
          <span className="text-xs text-[color:var(--color-ink-dim)]">
            将写入知识库 RESEARCH_NOTE（复盘叙述，category=SOP_REVIEW）。
          </span>
          <button
            type="button"
            className="rounded-md bg-[color:var(--color-ink)] px-3 py-1 text-xs text-[color:var(--color-bg)] disabled:opacity-60"
            disabled={save.saving}
            onClick={doReflux}
          >
            确认回流
          </button>
          <button
            type="button"
            className="rounded-md border border-[color:var(--color-line)] px-3 py-1 text-xs text-[color:var(--color-ink-dim)] disabled:opacity-60"
            disabled={save.saving}
            onClick={() => setConfirming(false)}
          >
            取消
          </button>
        </div>
      ) : (
        <button
          type="button"
          className="rounded-md border border-[color:var(--color-up)]/50 px-3 py-1.5 text-sm text-[color:var(--color-up)] disabled:opacity-60"
          disabled={save.saving}
          onClick={() => setConfirming(true)}
        >
          回流知识库
        </button>
      )}
      {save.error && <span className="text-sm text-[color:var(--color-down)]">{save.error}</span>}
    </div>
  );
}

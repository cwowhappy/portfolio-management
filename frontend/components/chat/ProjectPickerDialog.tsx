"use client";
// MS-32 F4（SOP v2 轻量收口）：策略草稿保存前的目标项目选择浮层。
// 拉本人 ACTIVE 项目（代码/名称/标题）+ 关键词过滤 → 选中即 saveStrategyDraft PUT（D9：落库
// =用户确认）；成功态给「查看项目」深链，失败行内错误条可重试。可达性沿 MS-31 ⑤ useDialogA11y
// 先例（形态 a：本组件随浮层条件挂载，active 恒真）。
import { useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { listProjects, saveStrategyDraft, type SaveStrategyDraftInput } from "@/lib/researchApi";
import type { StrategyDraft } from "@/lib/research-draft";
import type { ProjectView } from "@/lib/researchSchemas";
import { useSaveAction } from "@/lib/useSaveAction";
import { useDialogA11y } from "@/lib/useDialogA11y";

/** 聊天策略草稿 → PUT body：六字段整组覆盖（缺省 null，与研究页策略面板 draftBody 同语义——
 *  D13 整组覆盖，聊天侧草稿即用户确认的最新意图）；证伪条件项逐条按卡片同款「·」串联后
 *  换行拼接为 riskNotes 文本（riskItems 是草稿展示结构，项目侧 riskNotes 为自由文本）。 */
function strategyDraftToBody(draft: StrategyDraft): SaveStrategyDraftInput {
  return {
    thesis: draft.thesis ?? null,
    valuationLow: draft.valuationLow ?? null,
    valuationHigh: draft.valuationHigh ?? null,
    positionPlan: draft.positionPlan ?? null,
    buyConditions: draft.buyConditions ?? null,
    riskNotes: draft.riskItems?.length
      ? draft.riskItems
          .map((it) =>
            [it.kind, it.predicate, it.threshold == null ? null : `阈值 ${it.threshold}`, it.note]
              .filter(Boolean)
              .join(" · "),
          )
          .join("\n")
      : null,
  };
}

/** 条目行样式与取消按钮共用（浮层内二级操作，视觉弱于主操作链接）。 */
const btnGhost =
  "rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm text-[color:var(--color-ink-dim)] disabled:opacity-60";

export default function ProjectPickerDialog({ draft, onClose }: {
  draft: StrategyDraft;
  onClose: () => void;
}) {
  const [projects, setProjects] = useState<ProjectView[] | null>(null); // null=加载中
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reloadSeq, setReloadSeq] = useState(0); // 「重试加载」递增触发重拉
  const [keyword, setKeyword] = useState("");
  const [saved, setSaved] = useState<ProjectView | null>(null);
  const { saving, error, run } = useSaveAction("保存失败");
  // Escape/取消/背板同口径：保存在途不关——迟到响应翻转已关视图比留在浮层更糟
  const overlayRef = useRef<HTMLDivElement>(null);
  const onOverlayKeyDown = useDialogA11y(overlayRef, () => {
    if (!saving) onClose();
  });

  useEffect(() => {
    let cancelled = false; // 取消守卫（规范 3.1）：卸载/重试后丢弃迟到响应
    listProjects({ status: "ACTIVE" })
      .then((ps) => {
        if (!cancelled) setProjects(ps);
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setLoadError(e instanceof Error && e.message ? e.message : "项目列表加载失败");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [reloadSeq]);

  // 客户端过滤：候选集为本人 ACTIVE 全量（量级小），代码/名称/标题忽略大小写子串匹配
  const filtered = useMemo(() => {
    if (projects == null) return null;
    const k = keyword.trim().toLowerCase();
    if (!k) return projects;
    return projects.filter((p) =>
      [p.stockCode, p.stockName, p.title].some((v) => v.toLowerCase().includes(k)),
    );
  }, [projects, keyword]);

  const pick = (p: ProjectView) => {
    void run(async () => {
      await saveStrategyDraft(p.id, strategyDraftToBody(draft));
      setSaved(p);
    });
  };

  // 空态区分：全量为空（引导立项）vs 过滤后为空（换关键词）
  const emptyText =
    projects != null && projects.length === 0 ? "暂无进行中的研究项目" : "无匹配项目";

  /** 重试加载：清态回「加载中」再递增触发重拉（重置写在回调而非 effect，守 react-hooks 规则）。 */
  const retryLoad = () => {
    setProjects(null);
    setLoadError(null);
    setReloadSeq((s) => s + 1);
  };

  return (
    <div
      data-testid="project-picker-dialog"
      ref={overlayRef}
      tabIndex={-1}
      role="dialog"
      aria-modal="true"
      aria-label="保存策略草稿到研究项目"
      onKeyDown={onOverlayKeyDown}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      onClick={(e) => {
        if (e.target === e.currentTarget && !saving) onClose();
      }}
    >
      <div className="w-full max-w-md space-y-3 rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-bg)] p-4">
        {saved ? (
          <div className="space-y-3 py-4 text-center">
            <div className="text-sm">已保存到研究项目</div>
            <div className="flex items-center justify-center gap-3">
              <Link
                data-testid="draft-save-view-link"
                href={`/research/${saved.id}`}
                className="rounded-md bg-[color:var(--color-ink)] px-4 py-1.5 text-sm text-[color:var(--color-bg)]"
              >
                查看项目
              </Link>
              <button type="button" className={btnGhost} onClick={onClose}>
                关闭
              </button>
            </div>
          </div>
        ) : (
          <>
            <div className="text-sm font-medium text-[color:var(--color-ink)]">
              保存策略草稿到研究项目
            </div>
            <p className="text-xs text-[color:var(--color-ink-faint)]">
              暂存将整组覆盖所选项目的现有策略草稿
            </p>
            <input
              data-testid="project-picker-search"
              aria-label="搜索项目"
              className="w-full rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm"
              placeholder="搜索代码 / 名称 / 标题"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
            <div className="max-h-64 overflow-y-auto">
              {loadError ? (
                <div className="space-y-2 py-2 text-center">
                  <div className="text-sm text-[color:var(--color-down)]">{loadError}</div>
                  <button
                    type="button"
                    className="rounded-md border border-[color:var(--color-line)] px-3 py-1 text-xs text-[color:var(--color-ink-dim)]"
                    onClick={retryLoad}
                  >
                    重试加载
                  </button>
                </div>
              ) : filtered == null ? (
                <div className="py-3 text-center text-xs text-[color:var(--color-ink-faint)]">
                  项目列表加载中…
                </div>
              ) : filtered.length === 0 ? (
                <div className="space-y-2 py-3 text-center">
                  <div className="text-xs text-[color:var(--color-ink-faint)]">{emptyText}</div>
                  {projects != null && projects.length === 0 && (
                    <Link
                      href="/research"
                      className="text-xs text-[color:var(--color-accent)] hover:underline"
                    >
                      前往研究页立项
                    </Link>
                  )}
                </div>
              ) : (
                <ul className="divide-y divide-[color:var(--color-line-soft)]">
                  {filtered.map((p) => (
                    <li key={p.id}>
                      <button
                        type="button"
                        data-testid={`project-picker-row-${p.id}`}
                        disabled={saving}
                        className="flex w-full items-baseline justify-between gap-2 px-1 py-2 text-left hover:bg-[color:var(--color-panel)] disabled:opacity-60"
                        onClick={() => pick(p)}
                      >
                        <span className="shrink-0 text-[13px] text-[color:var(--color-ink)]">
                          {p.stockCode} {p.stockName}
                        </span>
                        <span className="min-w-0 flex-1 truncate text-xs text-[color:var(--color-ink-dim)]">
                          {p.title}
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
            {error && <div className="text-sm text-[color:var(--color-down)]">{error}</div>}
            {saving && (
              <div className="text-xs text-[color:var(--color-ink-faint)]">保存中…</div>
            )}
            <div className="flex justify-end">
              <button type="button" disabled={saving} className={btnGhost} onClick={onClose}>
                取消
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

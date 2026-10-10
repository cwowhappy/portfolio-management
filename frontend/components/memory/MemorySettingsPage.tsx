"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  deleteCuratedLine,
  deleteLedgerSection,
  editCuratedLine,
  fetchMemory,
  MemoryApiError,
  MemorySectionKind,
  MemoryView,
} from "@/lib/memoryApi";
import { useSaveAction } from "@/lib/useSaveAction";

// 记忆与画像设置页（MS-31）：F2 先交付下半「记忆管理区」，F3 在本文件上方补
// 「画像授权区」（授权低频在上、管理高频在下，首屏给管理）。

type LedgerFileView = MemoryView["ledgers"][number];
type LedgerSectionView = LedgerFileView["sections"][number];

/** kind 徽标中文标签（设计规格 §2.4：FLUSH→对话记忆 / EXTERNAL_EVENT→事件摘要） */
const KIND_LABEL: Record<MemorySectionKind, string> = {
  FLUSH: "对话记忆",
  EXTERNAL_EVENT: "事件摘要",
};

/** 删除策展行后日记账同步清除提示（Review Focus #2 文案红线：如实计数 + 残余可
 * 手动清理的软承诺，与「策展层不复活硬保证 / 日账本尽力清除软保证」验收口径一致） */
const purgedNotice = (n: number) => `已同步清除 ${n} 行日记账，残余可在日账本手动清理`;

export default function MemorySettingsPage() {
  return (
    <div className="mx-auto max-w-3xl space-y-6 p-6" data-testid="memory-settings">
      <h1 className="text-xl font-semibold">记忆与画像</h1>
      {/* F3：画像授权区（profile-config-form）将插在此处上方 */}
      <MemoryManagementSection />
    </div>
  );
}

/** 记忆管理区（F2）：可独立渲染的子块——策展层行级编辑/删除 + 日账本节级删除 */
export function MemoryManagementSection() {
  const [view, setView] = useState<MemoryView | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [editing, setEditing] = useState<{ lineIndex: number; draft: string } | null>(null);
  const requestSeqRef = useRef(0);
  const { saving, error: actionError, run } = useSaveAction("操作失败");

  // 服务端回执驱动（非乐观）：任何写操作成功后 reload 全视图；行去留只由服务端决定
  const reload = useCallback(() => {
    const seq = ++requestSeqRef.current;
    fetchMemory()
      .then((v) => {
        if (seq !== requestSeqRef.current) return; // 已有更新的 reload，丢弃过期响应
        setView(v);
        setLoadError(null);
        setEditing(null); // 新视图落地后行号/内容可能漂移，行内编辑一并收起
      })
      .catch((e) => {
        if (seq !== requestSeqRef.current) return;
        setLoadError(e instanceof Error ? e.message : "加载失败");
      });
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  // 409（hash 冲突）/410（行漂移）统一分流：提示已刷新并拉最新视图；
  // 其余异常（含 400 只读）经 useSaveAction 写行内红字
  const runMutation = (action: () => Promise<void>) =>
    run(async () => {
      setNotice(null);
      try {
        await action();
      } catch (e) {
        if (e instanceof MemoryApiError && (e.status === 409 || e.status === 410)) {
          setNotice("内容已变化，已刷新");
          setEditing(null);
          reload();
          return;
        }
        throw e;
      }
    });

  const onDeleteCuratedLine = (lineIndex: number, expectLine: string) => {
    const baseHash = view?.curated.hash;
    if (baseHash == null) return;
    runMutation(async () => {
      const r = await deleteCuratedLine({ lineIndex, expectLine, baseHash });
      setNotice(r.purgedLedgerLines > 0 ? purgedNotice(r.purgedLedgerLines) : null);
      reload();
    });
  };

  const onSaveEdit = () => {
    const baseHash = view?.curated.hash;
    if (baseHash == null || editing == null) return;
    const expectLine = view?.curated.lines[editing.lineIndex];
    if (expectLine === undefined) return;
    const { lineIndex, draft } = editing;
    runMutation(async () => {
      await editCuratedLine({ lineIndex, expectLine, newLine: draft, baseHash });
      setEditing(null);
      reload();
    });
  };

  const onDeleteSection = (ledger: LedgerFileView, section: LedgerSectionView) => {
    if (ledger.hash == null) return;
    if (!window.confirm(`删除该节？\n${section.header}`)) return;
    const { date, hash } = ledger;
    runMutation(async () => {
      await deleteLedgerSection(date, { sectionHeader: section.header, baseHash: hash });
      reload();
    });
  };

  const error = loadError ?? actionError;
  const isEmpty = view != null && view.curated.lines.length === 0 && view.ledgers.length === 0;
  // 按日倒序（date 为 ISO yyyy-MM-dd，字典序即时间序）
  const ledgersDesc = view == null ? [] : [...view.ledgers].sort((a, b) => (a.date < b.date ? 1 : -1));

  return (
    <section className="space-y-4" aria-labelledby="memory-management-heading">
      <h2 id="memory-management-heading" className="font-medium">记忆管理</h2>
      {error && (
        <div role="alert" className="text-sm text-[color:var(--color-up)]">
          {error}
        </div>
      )}
      {notice && (
        <div data-testid="memory-notice" className="text-sm text-[color:var(--color-ink-dim)]">
          {notice}
        </div>
      )}
      {view == null && loadError == null && (
        <div className="text-sm text-[color:var(--color-ink-faint)]">加载中…</div>
      )}
      {isEmpty && (
        <div data-testid="memory-empty-state" className="space-y-2 rounded-lg border border-[color:var(--color-line)] p-4 text-sm text-[color:var(--color-ink-dim)]">
          <p>还没有任何记忆。</p>
          <h3 className="font-medium text-[color:var(--color-ink)]">记忆如何产生</h3>
          <ul className="list-disc space-y-1 pl-5">
            <li>对话记忆：每轮对话结束后，系统自动从对话中抽取值得长期记住的要点，写入当日日账本。</li>
            <li>事件摘要：每日定时汇总当日投研动态，生成外部事件摘要写入当日日账本。</li>
          </ul>
        </div>
      )}
      {view != null && !isEmpty && (
        <>
          <div data-testid="memory-curated-list" className="space-y-1">
            <h3 className="text-sm font-medium">策展层（长期记忆 MEMORY.md）</h3>
            {view.curated.lines.map((line, i) =>
              editing?.lineIndex === i ? (
                <div key={i} className="flex items-center gap-2">
                  <input
                    data-testid="memory-curated-edit-input"
                    aria-label="编辑记忆行"
                    className="flex-1 rounded-md border border-[color:var(--color-line)] px-2 py-1 font-mono text-sm"
                    value={editing.draft}
                    onChange={(e) => setEditing({ lineIndex: i, draft: e.target.value })}
                  />
                  <button
                    data-testid="memory-curated-edit-save"
                    className="rounded border border-[color:var(--color-line)] px-3 py-1 text-sm disabled:opacity-50"
                    disabled={saving}
                    onClick={onSaveEdit}
                  >
                    保存
                  </button>
                  <button
                    data-testid="memory-curated-edit-cancel"
                    className="rounded border border-[color:var(--color-line)] px-3 py-1 text-sm"
                    onClick={() => setEditing(null)}
                  >
                    取消
                  </button>
                </div>
              ) : (
                <div key={i} className="group flex items-center gap-2">
                  <span className="flex-1 whitespace-pre-wrap font-mono text-sm">{line}</span>
                  <span className="flex gap-1 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
                    <button
                      data-testid={`memory-curated-edit-${i}`}
                      className="rounded border border-[color:var(--color-line)] px-2 py-0.5 text-xs disabled:opacity-50"
                      disabled={saving}
                      onClick={() => setEditing({ lineIndex: i, draft: line })}
                    >
                      编辑
                    </button>
                    <button
                      data-testid={`memory-curated-delete-${i}`}
                      className="rounded border border-[color:var(--color-line)] px-2 py-0.5 text-xs text-[color:var(--color-up)] disabled:opacity-50"
                      disabled={saving}
                      onClick={() => onDeleteCuratedLine(i, line)}
                    >
                      删除
                    </button>
                  </span>
                </div>
              ),
            )}
          </div>
          <div data-testid="memory-ledger-list" className="space-y-2">
            <h3 className="text-sm font-medium">日账本（按日倒序）</h3>
            {ledgersDesc.map((ledger, li) => (
              <details key={ledger.date} open={li === 0} className="rounded-lg border border-[color:var(--color-line)] p-3">
                <summary className="cursor-pointer text-sm">
                  <span className="font-mono">{ledger.date}</span>
                  <span className="ml-2 text-xs text-[color:var(--color-ink-faint)]">
                    （{ledger.sections.length} 节）
                  </span>
                </summary>
                <div className="mt-2 space-y-2">
                  {ledger.sections.map((sec, si) => (
                    <div
                      key={si}
                      data-testid={`memory-section-${ledger.date}-${si}`}
                      className="rounded-md border border-[color:var(--color-line)] p-2"
                    >
                      <div className="flex items-center justify-between gap-2">
                        <div className="flex min-w-0 items-center gap-2">
                          <span className="shrink-0 rounded bg-[color:var(--color-panel-2)] px-2 py-0.5 text-xs">
                            {KIND_LABEL[sec.kind]}
                          </span>
                          <span className="truncate font-mono text-xs text-[color:var(--color-ink-dim)]">
                            {sec.header}
                          </span>
                        </div>
                        <button
                          data-testid={`memory-section-delete-${ledger.date}-${si}`}
                          className="shrink-0 rounded border border-[color:var(--color-line)] px-2 py-0.5 text-xs text-[color:var(--color-up)] disabled:opacity-50"
                          disabled={saving}
                          onClick={() => onDeleteSection(ledger, sec)}
                        >
                          删除本节
                        </button>
                      </div>
                      <div className="mt-1 space-y-0.5">
                        {sec.lines
                          .filter((l) => l !== "")
                          .map((l, lj) => (
                            <p key={lj} className="whitespace-pre-wrap font-mono text-sm">
                              {l}
                            </p>
                          ))}
                      </div>
                    </div>
                  ))}
                </div>
              </details>
            ))}
          </div>
        </>
      )}
    </section>
  );
}

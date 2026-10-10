"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  deleteCuratedLine,
  deleteLedgerSection,
  editCuratedLine,
  fetchMemory,
  fetchProfileConfig,
  MemoryApiError,
  MemorySectionKind,
  MemoryView,
  ProfileConfigView,
  saveProfileConfig,
} from "@/lib/memoryApi";
import { useSaveAction } from "@/lib/useSaveAction";

// 记忆与画像设置页（MS-31）：上半「画像授权区」（F3）+ 下半「记忆管理区」（F2），
// 授权低频在上、管理高频在下（设计规格 §2.4，首屏给管理）。

type LedgerFileView = MemoryView["ledgers"][number];
type LedgerSectionView = LedgerFileView["sections"][number];

/** 四段开关本地草稿形态（与 ProfileConfigView 四键同构，PUT 全量保存体） */
type ProfileDraft = { style: boolean; industry: boolean; watchlist: boolean; holdings: boolean };

/** 四段展示元数据（需求决策 #3 四类数据口径：投资风格/关注行业/自选股/持仓汇总） */
const PROFILE_SECTIONS: { key: keyof ProfileDraft; label: string }[] = [
  { key: "style", label: "投资风格" },
  { key: "industry", label: "关注行业" },
  { key: "watchlist", label: "自选股" },
  { key: "holdings", label: "持仓汇总" },
];

const toDraft = (v: ProfileConfigView): ProfileDraft => ({
  style: v.style.enabled,
  industry: v.industry.enabled,
  watchlist: v.watchlist.enabled,
  holdings: v.holdings.enabled,
});

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
      <ProfileConfigSection />
      <MemoryManagementSection />
    </div>
  );
}

/**
 * 画像注入授权区（F3，设计规格 §3.5）：四段独立开关（默认关 opt-in，PUT 全量保存）
 * + 每段折叠预览（preview 为注入侧同一渲染路径直出的段全串——「所见即所注入」，
 * 数据源失败/空段 null → 「暂不可用」；disabled 段也出预览「先看再开」）。
 * 保存成功以 PUT 回执（ProfileConfigView）覆盖本地，非乐观。
 */
export function ProfileConfigSection() {
  const [view, setView] = useState<ProfileConfigView | null>(null);
  const [draft, setDraft] = useState<ProfileDraft | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const { saving, error: actionError, run } = useSaveAction("保存失败");

  useEffect(() => {
    fetchProfileConfig()
      .then((v) => {
        setView(v);
        setDraft(toDraft(v));
        setLoadError(null);
      })
      .catch((e) => setLoadError(e instanceof Error ? e.message : "加载失败"));
  }, []);

  // 全量 PUT 四布尔；成功以服务端回执覆盖本地（回执含最新预览，一并刷新）
  const onSave = () => {
    if (draft == null) return;
    run(async () => {
      const saved = await saveProfileConfig(draft);
      setView(saved);
      setDraft(toDraft(saved));
    });
  };

  const error = loadError ?? actionError;

  return (
    <section
      data-testid="profile-config-form"
      className="space-y-4"
      aria-labelledby="profile-config-heading"
    >
      <h2 id="profile-config-heading" className="font-medium">画像注入授权</h2>
      {error && (
        <div role="alert" className="text-sm text-[color:var(--color-up)]">
          {error}
        </div>
      )}
      {view == null && loadError == null && (
        <div className="text-sm text-[color:var(--color-ink-faint)]">画像加载中…</div>
      )}
      {view != null && draft != null && (
        <div className="space-y-3">
          <p className="text-sm text-[color:var(--color-ink-dim)]">
            开启后对应画像将在对话时注入助手上下文，仅用于个性化参考；每段可先展开预览再决定是否授权。
          </p>
          {PROFILE_SECTIONS.map(({ key, label }) => (
            <div key={key} className="space-y-1 rounded-lg border border-[color:var(--color-line)] p-3">
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  data-testid={`profile-toggle-${key}`}
                  checked={draft[key]}
                  disabled={saving}
                  onChange={(e) => setDraft({ ...draft, [key]: e.target.checked })}
                />
                <span className="font-medium">{label}</span>
              </label>
              <details data-testid={`profile-preview-${key}`}>
                <summary className="cursor-pointer text-xs text-[color:var(--color-ink-dim)]">
                  展开预览将注入的内容
                </summary>
                <div className="mt-1">
                  {view[key].preview ? (
                    <pre className="overflow-x-auto whitespace-pre-wrap rounded-md border border-[color:var(--color-line)] p-2 font-mono text-sm">
                      {view[key].preview}
                    </pre>
                  ) : (
                    <p className="text-sm text-[color:var(--color-ink-faint)]">暂不可用</p>
                  )}
                </div>
              </details>
            </div>
          ))}
          <button
            data-testid="profile-config-save"
            className="rounded border border-[color:var(--color-line)] px-3 py-1 text-sm disabled:opacity-50"
            disabled={saving}
            onClick={onSave}
          >
            保存
          </button>
        </div>
      )}
    </section>
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

  // 服务端回执驱动（非乐观）：任何写操作成功后 reload 全视图；行去留只由服务端决定。
  // 返回 Promise 且失败上抛（内部已 setLoadError）：调用方按需感知刷新成败——
  // 终审 F-6：notice 须随 reload 成文，刷新失败时不得给「已同步清除」类失真提示
  //（setState 全部收在 .then/.catch 回调内——effect 直呼本函数须过 set-state-in-effect 规则）
  const reload = useCallback(() => {
    const seq = ++requestSeqRef.current;
    return fetchMemory()
      .then((v) => {
        if (seq !== requestSeqRef.current) return; // 已有更新的 reload，丢弃过期响应
        setView(v);
        setLoadError(null);
        setEditing(null); // 新视图落地后行号/内容可能漂移，行内编辑一并收起
      })
      .catch((e) => {
        if (seq !== requestSeqRef.current) return;
        setLoadError(e instanceof Error ? e.message : "加载失败");
        throw e;
      });
  }, []);

  useEffect(() => {
    // 失败已在 reload 内经 setLoadError 呈现，此处仅防未处理 rejection（终审 F-6）
    reload().catch(() => {});
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
          setEditing(null);
          await reload(); // 刷新失败上抛走 useSaveAction 红字（notice 不成文，终审 F-6 同口径）
          setNotice("内容已变化，已刷新");
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
      // notice 随 reload 成文（终审 F-6）：删除成功但刷新失败 → 不提示「已同步清除」，
      // 失败经 reload 上抛走 useSaveAction 行内红字
      await reload();
      setNotice(r.purgedLedgerLines > 0 ? purgedNotice(r.purgedLedgerLines) : null);
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
      await reload();
    });
  };

  const onDeleteSection = (ledger: LedgerFileView, section: LedgerSectionView) => {
    if (ledger.hash == null) return;
    if (!window.confirm(`删除该节？\n${section.header}`)) return;
    const { date, hash } = ledger;
    runMutation(async () => {
      await deleteLedgerSection(date, { sectionHeader: section.header, baseHash: hash });
      await reload();
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
                  {/* 只读标题行（行 0 / '# ' 开头，与后端 WorkspaceMemoryFileAdapter 同口径）
                      UI 预拦不渲染行级操作，普通行照常 */}
                  {!(i === 0 || line.startsWith("# ")) && (
                    <span className="flex gap-1 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
                      <button
                        data-testid={`memory-curated-edit-${i}`}
                        aria-label={`编辑第 ${i + 1} 行`}
                        className="rounded border border-[color:var(--color-line)] px-2 py-0.5 text-xs disabled:opacity-50"
                        disabled={saving}
                        onClick={() => setEditing({ lineIndex: i, draft: line })}
                      >
                        编辑
                      </button>
                      <button
                        data-testid={`memory-curated-delete-${i}`}
                        aria-label={`删除第 ${i + 1} 行`}
                        className="rounded border border-[color:var(--color-line)] px-2 py-0.5 text-xs text-[color:var(--color-up)] disabled:opacity-50"
                        disabled={saving}
                        onClick={() => onDeleteCuratedLine(i, line)}
                      >
                        删除
                      </button>
                    </span>
                  )}
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

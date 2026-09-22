"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { fetchRules, fetchWikiEntries } from "@/lib/wikiApi";
import type { PrincipleRuleView, WikiEntryType, WikiEntryView } from "@/lib/types";
import NotePanel from "./NotePanel";
import RulePanel from "./RulePanel";

export type WikiTab = "principle" | "book" | "concept" | "research";

const TAB_LABELS: Record<WikiTab, string> = {
  principle: "原则纪律", book: "读书笔记", concept: "概念速查", research: "研究笔记",
};

const TAB_ENTRY_TYPES: Record<Exclude<WikiTab, "principle">, WikiEntryType> = {
  book: "BOOK_NOTE", concept: "CONCEPT", research: "RESEARCH_NOTE",
};

export default function WikiBoard({ initialTab = "principle" }: { initialTab?: WikiTab }) {
  const [tab, setTab] = useState<WikiTab>(initialTab);
  const [entries, setEntries] = useState<WikiEntryView[]>([]);
  const [rules, setRules] = useState<PrincipleRuleView[]>([]);
  const [error, setError] = useState<string | null>(null);
  const requestSeqRef = useRef(0);

  const reload = useCallback(() => {
    const seq = ++requestSeqRef.current;
    const type = tab === "principle" ? undefined : TAB_ENTRY_TYPES[tab];
    Promise.all([
      fetchWikiEntries(type).then((e) => { if (seq === requestSeqRef.current) setEntries(e); }),
      fetchRules().then((r) => { if (seq === requestSeqRef.current) setRules(r); }),
    ]).catch((err) => {
      if (seq !== requestSeqRef.current) return; // 已有更新的 reload，丢弃过期响应
      setError(err instanceof Error ? err.message : "加载失败");
    });
  }, [tab]);

  useEffect(() => { reload(); }, [reload]);

  if (error) return <div className="p-8 text-[color:var(--color-ink-dim)]">加载失败：{error}</div>;

  return (
    <div className="mx-auto max-w-6xl px-6 py-8 space-y-6" data-testid="wiki-board">
      <div className="flex items-center justify-between">
        <h1 className="font-[family-name:var(--font-display)] text-2xl">投资知识库</h1>
        <div className="flex gap-2">
          {(Object.keys(TAB_LABELS) as WikiTab[]).map((t) => (
            <button key={t} data-testid={`wiki-tab-${t}`}
              className={tab === t ? tabActive : tabInactive}
              onClick={() => setTab(t)}>
              {TAB_LABELS[t]}
            </button>
          ))}
        </div>
      </div>
      {tab === "principle"
        ? <RulePanel rules={rules} onChanged={reload} />
        : <NotePanel type={TAB_ENTRY_TYPES[tab]} entries={entries} onChanged={reload} />}
    </div>
  );
}

const tabActive = "rounded-md px-3 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]";
const tabInactive = "rounded-md px-3 py-1.5 text-sm border border-[color:var(--color-line)]";

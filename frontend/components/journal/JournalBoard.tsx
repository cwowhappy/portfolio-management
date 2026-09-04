"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { fetchEntries, fetchTimeline } from "@/lib/journalApi";
import type { JournalEntryView, TimelineEventView } from "@/lib/types";
import EntryEditor from "./EntryEditor";
import EntryList from "./EntryList";
import TimelineView from "./TimelineView";

export default function JournalBoard() {
  const [tab, setTab] = useState<"timeline" | "entries">("timeline");
  const [events, setEvents] = useState<TimelineEventView[]>([]);
  const [entries, setEntries] = useState<JournalEntryView[]>([]);
  const [editing, setEditing] = useState<JournalEntryView | null>(null);
  const [error, setError] = useState<string | null>(null);
  // 时间线日期范围过滤（FR-E2 ?from=/?to=），空字符串表示不限
  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");
  const requestSeqRef = useRef(0);

  const reload = useCallback(() => {
    const seq = ++requestSeqRef.current;
    Promise.all([
      fetchTimeline(fromDate || undefined, toDate || undefined),
      fetchEntries(),
    ])
      .then(([e, en]) => {
        if (seq !== requestSeqRef.current) return; // 已有更新的 reload，丢弃过期响应
        setEvents(e);
        setEntries(en);
      })
      .catch((err) => {
        if (seq !== requestSeqRef.current) return;
        setError(err instanceof Error ? err.message : "加载失败");
      });
  }, [fromDate, toDate]);

  useEffect(() => { reload(); }, [reload]);

  const onSaved = () => { setEditing(null); reload(); };
  const clearRange = () => { setFromDate(""); setToDate(""); };

  if (error) return <div className="p-8 text-[color:var(--color-ink-dim)]">加载失败：{error}</div>;

  return (
    <div className="mx-auto max-w-6xl px-6 py-8 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="font-[family-name:var(--font-display)] text-2xl">投资决策记录</h1>
        <div className="flex gap-2">
          <button className={tab === "timeline" ? tabActive : tabInactive} onClick={() => setTab("timeline")}>时间线</button>
          <button className={tab === "entries" ? tabActive : tabInactive} onClick={() => setTab("entries")}>记录</button>
        </div>
      </div>
      {tab === "timeline" && (
        <div className="flex flex-wrap items-center gap-2" data-testid="timeline-filter">
          <input
            type="date"
            aria-label="开始日期"
            className="rounded-md border border-[color:var(--color-line)] px-2 py-1 text-sm"
            value={fromDate}
            onChange={(e) => setFromDate(e.target.value)}
          />
          <span className="text-sm text-[color:var(--color-ink-faint)]">至</span>
          <input
            type="date"
            aria-label="结束日期"
            className="rounded-md border border-[color:var(--color-line)] px-2 py-1 text-sm"
            value={toDate}
            onChange={(e) => setToDate(e.target.value)}
          />
          {(fromDate || toDate) && (
            <button
              className="rounded-md border border-[color:var(--color-line)] px-3 py-1 text-xs text-[color:var(--color-ink-dim)]"
              onClick={clearRange}
            >
              重置
            </button>
          )}
        </div>
      )}
      {tab === "timeline" ? <TimelineView events={events} /> : (
        <>
          <EntryEditor key={editing?.id ?? "new"} editing={editing} onSaved={onSaved} onCancel={() => setEditing(null)} />
          <EntryList entries={entries} onEdit={setEditing} onChanged={reload} />
        </>
      )}
    </div>
  );
}

const tabActive = "rounded-md px-3 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]";
const tabInactive = "rounded-md px-3 py-1.5 text-sm border border-[color:var(--color-line)]";

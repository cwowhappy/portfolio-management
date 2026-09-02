"use client";

import { useCallback, useEffect, useState } from "react";
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

  const reload = useCallback(() => {
    Promise.all([fetchTimeline(), fetchEntries()])
      .then(([e, en]) => { setEvents(e); setEntries(en); })
      .catch((err) => setError(err instanceof Error ? err.message : "加载失败"));
  }, []);

  useEffect(() => { reload(); }, [reload]);

  const onSaved = () => { setEditing(null); reload(); };

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

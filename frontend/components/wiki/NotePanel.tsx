"use client";

import { useState } from "react";
import MarkdownView from "@/components/shared/MarkdownView";
import { deleteWikiEntry } from "@/lib/wikiApi";
import type { WikiEntryType, WikiEntryView } from "@/lib/types";
import NoteEditor from "./NoteEditor";

export default function NotePanel({ type, entries, onChanged }: {
  type: WikiEntryType;
  entries: WikiEntryView[];
  onChanged: () => void;
}) {
  const [editing, setEditing] = useState<WikiEntryView | null>(null);
  const [expanded, setExpanded] = useState<number | null>(null);

  return (
    <div className="space-y-6">
      <NoteEditor key={editing?.id ?? "new"} type={type} editing={editing}
        onSaved={() => { setEditing(null); onChanged(); }}
        onCancel={() => setEditing(null)} />
      <div className="space-y-2" data-testid="wiki-note-list">
        {entries.length === 0 && <div className="text-sm text-[color:var(--color-ink-faint)]">暂无条目</div>}
        {entries.map((e) => (
          <div key={e.id} className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-4">
            <div className="flex items-center justify-between gap-2">
              <button data-testid={`wiki-note-${e.id}`} type="button"
                className="text-left font-medium hover:underline"
                onClick={() => setExpanded(expanded === e.id ? null : e.id)}>
                {e.title}
                {e.category && <span className="ml-2 text-xs text-[color:var(--color-ink-faint)]">{e.category}</span>}
                {e.industryCode && <span className="ml-2 text-xs text-[color:var(--color-ink-faint)]">行业 {e.industryCode}</span>}
              </button>
              <div className="flex shrink-0 gap-2 text-xs text-[color:var(--color-ink-dim)]">
                <button type="button" className="hover:underline" onClick={() => setEditing(e)}>编辑</button>
                <button type="button" className="hover:underline"
                  onClick={() => { if (confirm("删除该条目？")) deleteWikiEntry(e.id).then(onChanged).catch(() => {}); }}>
                  删除
                </button>
              </div>
            </div>
            {expanded === e.id && <div className="mt-3"><MarkdownView content={e.content} /></div>}
          </div>
        ))}
      </div>
    </div>
  );
}

"use client";

import { deleteEntry, JOURNAL_ENTRY_TYPE_LABELS } from "@/lib/journalApi";
import type { JournalEntryView } from "@/lib/types";

export default function EntryList({ entries, onEdit, onChanged }: {
  entries: JournalEntryView[]; onEdit: (e: JournalEntryView) => void; onChanged: () => void;
}) {
  const remove = async (id: number) => { if (confirm("删除该记录？")) { await deleteEntry(id); onChanged(); } };

  if (entries.length === 0) {
    return <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 text-sm text-[color:var(--color-ink-faint)]">暂无记录</div>;
  }
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5" data-testid="entry-list">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">记录列表</div>
      <ul className="space-y-2">
        {entries.map((e) => (
          <li key={e.id} className="flex items-center justify-between text-sm" data-testid="entry-item">
            <span>
              <span className="mr-2 text-xs text-[color:var(--color-ink-faint)]">{JOURNAL_ENTRY_TYPE_LABELS[e.type]}</span>
              {e.title}
              {e.stockName && <span className="ml-2 text-xs text-[color:var(--color-ink-dim)]">{e.stockName}</span>}
            </span>
            <span className="flex gap-2">
              <button className="rounded-md px-2 py-1 text-xs border border-[color:var(--color-line)]" onClick={() => onEdit(e)}>编辑</button>
              <button className="rounded-md px-2 py-1 text-xs border border-[color:var(--color-line)]" onClick={() => remove(e.id)}>删除</button>
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

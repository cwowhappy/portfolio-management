"use client";

import { TIMELINE_EVENT_TYPE_LABELS } from "@/lib/journalApi";
import type { TimelineEventView } from "@/lib/types";

export default function TimelineView({ events }: { events: TimelineEventView[] }) {
  if (events.length === 0) {
    return (
      <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 text-sm text-[color:var(--color-ink-faint)]">
        暂无事件。买入/卖出交易与备忘、研究笔记、复盘会按事件日汇总在这里。
      </div>
    );
  }
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5" data-testid="timeline">
      <ul className="space-y-3">
        {events.map((e, i) => (
          <li key={i} className="flex gap-3 text-sm" data-testid="timeline-event">
            <span className="w-20 shrink-0 text-xs text-[color:var(--color-ink-faint)]">{e.date}</span>
            <span className="w-20 shrink-0 text-xs">{TIMELINE_EVENT_TYPE_LABELS[e.type] ?? e.type}</span>
            <div className="min-w-0">
              <div className="font-medium">{e.title}</div>
              {e.description && <div className="text-[color:var(--color-ink-dim)] truncate">{e.description}</div>}
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}

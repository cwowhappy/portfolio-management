# P3 前端 journal 页面 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `/journal` 页面：投资时间线视图 + 记录列表 + 类型化编辑器（备忘/研究笔记/复盘），并接入顶部导航。

**Architecture:** 沿用 M08/M07 前端的同源反代模式——`app/api/journal/[...path]` Route Handler 经 `relay()` 反代到后端 `/api/journal/**`，`lib/journalApi.ts` 用 zod 契约在边界校验 DTO，`app/journal/page.tsx` 用 `RequireAuth` 包裹。页面由 `JournalBoard` 编排（时间线 / 记录两个标签），子组件 `TimelineView` / `EntryList` / `EntryEditor` 各司其职。

**Tech Stack:** Next.js 15 · React 19 · Tailwind 4 · zod · vitest + React Testing Library · Playwright

**Spec:** `features/journal/01-requirement/需求规格说明.md`

## Global Constraints

- 组件禁直接 `fetch`，一律走 `lib/*Api.ts`（eslint `no-restricted-imports` 规则强制）。
- 后端 DTO 在 `lib/schemas.ts` 用 zod 校验，`lib/types.ts` 存类型，两者与后端 DTO 字段一一对应。
- 页面需登录（`RequireAuth`）；反代透传 Cookie（`relay`），无 CORS。
- 记录类型固定 4 类，顺序 BUY_MEMO/SELL_MEMO/RESEARCH_NOTE/REVIEW；中文标签用 `JOURNAL_ENTRY_TYPE_LABELS`。
- 时间线事件 7 类，标签用 `TIMELINE_EVENT_TYPE_LABELS`。
- 前端覆盖率（V8 语句/分支）≥80%，lint 纳入 `make test`。
- 本里程碑编辑器用**手填 stockCode/stockName/tradeId**（不接行情搜索与交易下拉，YAGNI，后续增强）。

---

### Task 1: 反代路由 + 契约 + API 客户端

**Files:**
- Create: `frontend/app/api/journal/[...path]/route.ts`
- Modify: `frontend/lib/schemas.ts`（追加 journal 段）
- Modify: `frontend/lib/types.ts`（追加 journal 段）
- Create: `frontend/lib/journalApi.ts`

**Interfaces:**
- Produces: 反代 `GET/POST/PUT/DELETE`；schema/type 常量；`fetchEntries/fetchTimeline/createEntry/updateEntry/deleteEntry` 及标签映射常量。

- [ ] **Step 1: 反代路由**

```ts
import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

async function resolve(ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  return "/api/journal" + (path.length ? "/" + path.join("/") : "");
}

export async function GET(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(ctx), "GET", req);
}
export async function POST(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(ctx), "POST", req, await req.text());
}
export async function PUT(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(ctx), "PUT", req, await req.text());
}
export async function DELETE(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(ctx), "DELETE", req);
}
```

- [ ] **Step 2: 追加 zod schema（`schemas.ts` 末尾）**

```ts
// —— 投资决策记录（/api/journal/**，与后端 JournalController 的 DTO 对齐）——

export const JournalEntryTypeSchema = z.enum(["BUY_MEMO", "SELL_MEMO", "RESEARCH_NOTE", "REVIEW"]);
export const PeriodTypeSchema = z.enum(["QUARTERLY", "ANNUAL"]);
export const JournalEntryViewSchema = z.object({
  id: z.number(),
  type: JournalEntryTypeSchema,
  stockCode: z.string().nullable(),
  stockName: z.string().nullable(),
  tradeId: z.number().nullable(),
  title: z.string(),
  content: z.string(),
  targetPrice: z.number().nullable(),
  stopLoss: z.number().nullable(),
  periodType: PeriodTypeSchema.nullable(),
  periodStart: z.string().nullable(),
  periodEnd: z.string().nullable(),
  eventDate: z.string(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export const TimelineEventTypeSchema = z.enum([
  "BUY", "SELL", "DIVIDEND", "BUY_MEMO", "SELL_MEMO", "RESEARCH_NOTE", "REVIEW",
]);
export const TimelineEventViewSchema = z.object({
  type: TimelineEventTypeSchema,
  date: z.string(),
  title: z.string(),
  description: z.string(),
  stockCode: z.string().nullable(),
  stockName: z.string().nullable(),
  refId: z.number().nullable(),
  refType: z.string(),
});
```

- [ ] **Step 3: 追加类型（`types.ts` 末尾）**

```ts
// —— 投资决策记录 ——

export type JournalEntryType = "BUY_MEMO" | "SELL_MEMO" | "RESEARCH_NOTE" | "REVIEW";
export type PeriodType = "QUARTERLY" | "ANNUAL";
export interface JournalEntryView {
  id: number;
  type: JournalEntryType;
  stockCode: string | null;
  stockName: string | null;
  tradeId: number | null;
  title: string;
  content: string;
  targetPrice: number | null;
  stopLoss: number | null;
  periodType: PeriodType | null;
  periodStart: string | null;
  periodEnd: string | null;
  eventDate: string;
  createdAt: string;
  updatedAt: string;
}
export type TimelineEventType = "BUY" | "SELL" | "DIVIDEND" | "BUY_MEMO" | "SELL_MEMO" | "RESEARCH_NOTE" | "REVIEW";
export interface TimelineEventView {
  type: TimelineEventType;
  date: string;
  title: string;
  description: string;
  stockCode: string | null;
  stockName: string | null;
  refId: number | null;
  refType: string;
}
```

- [ ] **Step 4: API 客户端**

```ts
import { z } from "zod";
import { JournalEntryViewSchema, TimelineEventViewSchema } from "./schemas";
import type { JournalEntryType, JournalEntryView, PeriodType, TimelineEventView } from "./types";

async function request<T>(path: string, method: string, body?: unknown, schema?: z.ZodType<T>): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: body !== undefined ? { "Content-Type": "application/json" } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    cache: "no-store",
  });
  if (!res.ok) {
    let message = "请求失败";
    try { const b = await res.json(); if (b?.message) message = b.message; } catch { /* ignore */ }
    throw new Error(message);
  }
  if (res.status === 204) return undefined as T;
  const data: unknown = await res.json();
  return schema ? schema.parse(data) : (data as T);
}

export interface EntryInput {
  type: JournalEntryType;
  stockCode?: string | null;
  stockName?: string | null;
  tradeId?: number | null;
  title: string;
  content: string;
  targetPrice?: number | null;
  stopLoss?: number | null;
  periodType?: PeriodType | null;
  periodStart?: string | null;
  periodEnd?: string | null;
  eventDate: string;
}

export const JOURNAL_ENTRY_TYPE_LABELS: Record<JournalEntryType, string> = {
  BUY_MEMO: "买入备忘", SELL_MEMO: "卖出备忘", RESEARCH_NOTE: "研究笔记", REVIEW: "定期复盘",
};
export const PERIOD_TYPE_LABELS: Record<PeriodType, string> = { QUARTERLY: "季度", ANNUAL: "年度" };
export const TIMELINE_EVENT_TYPE_LABELS: Record<string, string> = {
  BUY: "买入", SELL: "卖出", DIVIDEND: "分红",
  BUY_MEMO: "买入备忘", SELL_MEMO: "卖出备忘", RESEARCH_NOTE: "研究笔记", REVIEW: "复盘",
};

export const fetchEntries = (type?: JournalEntryType) =>
  request<JournalEntryView[]>(`/api/journal/entries${type ? `?type=${type}` : ""}`, "GET", undefined, z.array(JournalEntryViewSchema));
export const fetchTimeline = (from?: string, to?: string) => {
  const qs = [from && `from=${from}`, to && `to=${to}`].filter(Boolean).join("&");
  return request<TimelineEventView[]>(`/api/journal/timeline${qs ? `?${qs}` : ""}`, "GET", undefined, z.array(TimelineEventViewSchema));
};
export const createEntry = (cmd: EntryInput) =>
  request<JournalEntryView>("/api/journal/entries", "POST", cmd, JournalEntryViewSchema);
export const updateEntry = (id: number, cmd: EntryInput) =>
  request<JournalEntryView>(`/api/journal/entries/${id}`, "PUT", cmd, JournalEntryViewSchema);
export const deleteEntry = (id: number) => request<void>(`/api/journal/entries/${id}`, "DELETE");
```

- [ ] **Step 5: Commit**

```bash
git add frontend/app/api/journal/ frontend/lib/schemas.ts frontend/lib/types.ts frontend/lib/journalApi.ts
git commit -m "feat(journal): 前端反代路由 + zod 契约 + API 客户端"
```

---

### Task 2: /journal 页面与组件 + 导航

**Files:**
- Create: `frontend/app/journal/page.tsx`
- Create: `frontend/components/journal/JournalBoard.tsx`
- Create: `frontend/components/journal/TimelineView.tsx`
- Create: `frontend/components/journal/EntryList.tsx`
- Create: `frontend/components/journal/EntryEditor.tsx`
- Modify: `frontend/app/layout.tsx`（新增「决策」导航链接）

**Interfaces:**
- Consumes: `lib/journalApi.ts`（Task 1）。
- Produces: `JournalBoard`（编排）、`TimelineView({ events })`、`EntryList({ entries, onEdit, onChanged })`、`EntryEditor({ editing, onSaved, onCancel })`。

- [ ] **Step 1: 页面**

`app/journal/page.tsx`:
```tsx
import JournalBoard from "@/components/journal/JournalBoard";
import { RequireAuth } from "@/components/auth/RequireAuth";

export default function JournalPage() {
  return (
    <RequireAuth>
      <JournalBoard />
    </RequireAuth>
  );
}
```

- [ ] **Step 2: 编排组件**

`components/journal/JournalBoard.tsx`:
```tsx
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
          <EntryEditor editing={editing} onSaved={onSaved} onCancel={() => setEditing(null)} />
          <EntryList entries={entries} onEdit={setEditing} onChanged={reload} />
        </>
      )}
    </div>
  );
}

const tabActive = "rounded-md px-3 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]";
const tabInactive = "rounded-md px-3 py-1.5 text-sm border border-[color:var(--color-line)]";
```

- [ ] **Step 3: 时间线视图**

`components/journal/TimelineView.tsx`:
```tsx
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
```

- [ ] **Step 4: 记录列表**

`components/journal/EntryList.tsx`:
```tsx
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
```

- [ ] **Step 5: 记录编辑器（类型化字段）**

`components/journal/EntryEditor.tsx`:
```tsx
"use client";

import { useState } from "react";
import { createEntry, updateEntry, JOURNAL_ENTRY_TYPE_LABELS, PERIOD_TYPE_LABELS, type EntryInput } from "@/lib/journalApi";
import type { JournalEntryType, JournalEntryView, PeriodType } from "@/lib/types";

const TYPES: JournalEntryType[] = ["BUY_MEMO", "SELL_MEMO", "RESEARCH_NOTE", "REVIEW"];

export default function EntryEditor({ editing, onSaved, onCancel }: {
  editing: JournalEntryView | null; onSaved: () => void; onCancel: () => void;
}) {
  const [type, setType] = useState<JournalEntryType>(editing?.type ?? "BUY_MEMO");
  const [title, setTitle] = useState(editing?.title ?? "");
  const [content, setContent] = useState(editing?.content ?? "");
  const [eventDate, setEventDate] = useState(editing?.eventDate ?? new Date().toISOString().slice(0, 10));
  const [stockCode, setStockCode] = useState(editing?.stockCode ?? "");
  const [stockName, setStockName] = useState(editing?.stockName ?? "");
  const [tradeId, setTradeId] = useState(editing?.tradeId?.toString() ?? "");
  const [targetPrice, setTargetPrice] = useState(editing?.targetPrice?.toString() ?? "");
  const [stopLoss, setStopLoss] = useState(editing?.stopLoss?.toString() ?? "");
  const [periodType, setPeriodType] = useState<PeriodType>(editing?.periodType ?? "QUARTERLY");
  const [periodStart, setPeriodStart] = useState(editing?.periodStart ?? "");
  const [periodEnd, setPeriodEnd] = useState(editing?.periodEnd ?? "");
  const [error, setError] = useState<string | null>(null);

  const isMemo = type === "BUY_MEMO" || type === "SELL_MEMO";
  const isBuyMemo = type === "BUY_MEMO";
  const isReview = type === "REVIEW";

  const save = async () => {
    setError(null);
    const input: EntryInput = {
      type,
      title: title.trim(),
      content,
      eventDate,
      stockCode: (isMemo || type === "RESEARCH_NOTE") ? (stockCode.trim() || null) : null,
      stockName: stockName.trim() || null,
      tradeId: isMemo && tradeId ? Number(tradeId) : null,
      targetPrice: isBuyMemo && targetPrice ? Number(targetPrice) : null,
      stopLoss: isBuyMemo && stopLoss ? Number(stopLoss) : null,
      periodType: isReview ? periodType : null,
      periodStart: isReview ? (periodStart || null) : null,
      periodEnd: isReview ? (periodEnd || null) : null,
    };
    if (!input.title) { setError("标题不能为空"); return; }
    if (!input.content) { setError("内容不能为空"); return; }
    try {
      if (editing) await updateEntry(editing.id, input);
      else await createEntry(input);
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : "保存失败");
    }
  };

  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">{editing ? "编辑记录" : "新建记录"}</div>
      <div className="flex flex-wrap gap-2 mb-3">
        {TYPES.map((t) => (
          <button key={t} type="button"
            className={type === t ? "rounded-md px-3 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]" : "rounded-md px-3 py-1.5 text-sm border border-[color:var(--color-line)]"}
            onClick={() => setType(t)}>
            {JOURNAL_ENTRY_TYPE_LABELS[t]}
          </button>
        ))}
      </div>

      <div className="grid grid-cols-2 gap-3 mb-3 max-w-xl">
        <input className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm col-span-2" placeholder="标题" value={title} onChange={(e) => setTitle(e.target.value)} />
        {(isMemo || type === "RESEARCH_NOTE") && (
          <>
            <input className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm" placeholder="股票代码（如 600519）" value={stockCode} onChange={(e) => setStockCode(e.target.value)} />
            <input className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm" placeholder="股票名称（如 贵州茅台）" value={stockName} onChange={(e) => setStockName(e.target.value)} />
          </>
        )}
        {isMemo && (
          <input className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm" placeholder="关联交易 ID（可选）" value={tradeId} onChange={(e) => setTradeId(e.target.value)} />
        )}
        {isBuyMemo && (
          <>
            <input className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm" placeholder="目标价（可选）" value={targetPrice} onChange={(e) => setTargetPrice(e.target.value)} />
            <input className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm" placeholder="止损价（可选）" value={stopLoss} onChange={(e) => setStopLoss(e.target.value)} />
          </>
        )}
        {isReview && (
          <>
            <select className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm" value={periodType} onChange={(e) => setPeriodType(e.target.value as PeriodType)}>
              <option value="QUARTERLY">{PERIOD_TYPE_LABELS.QUARTERLY}</option>
              <option value="ANNUAL">{PERIOD_TYPE_LABELS.ANNUAL}</option>
            </select>
            <input className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm" type="date" value={periodStart} onChange={(e) => setPeriodStart(e.target.value)} />
            <input className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm" type="date" value={periodEnd} onChange={(e) => setPeriodEnd(e.target.value)} />
          </>
        )}
        <input className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm" type="date" value={eventDate} onChange={(e) => setEventDate(e.target.value)} />
      </div>

      <textarea className="w-full rounded-md border border-[color:var(--color-line)] px-3 py-2 text-sm mb-3 min-h-32" placeholder="内容（Markdown）" value={content} onChange={(e) => setContent(e.target.value)} />
      {error && <div className="text-sm text-[color:var(--color-down)] mb-3">{error}</div>}
      <div className="flex gap-2">
        <button className="rounded-md px-4 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]" onClick={save}>{editing ? "保存修改" : "保存记录"}</button>
        {editing && <button className="rounded-md px-4 py-1.5 text-sm border border-[color:var(--color-line)]" onClick={onCancel}>取消</button>}
      </div>
    </div>
  );
}
```

- [ ] **Step 6: 导航链接**

在 `app/layout.tsx` 的「配置」链接后插入：

```tsx
<Link
  href="/journal"
  className="rounded-md px-3 py-1.5 text-[color:var(--color-ink-dim)] transition-colors hover:bg-[color:var(--color-panel)] hover:text-[color:var(--color-ink)]"
>
  决策
</Link>
```

- [ ] **Step 7: Commit**

```bash
git add frontend/app/journal/ frontend/components/journal/ frontend/app/layout.tsx
git commit -m "feat(journal): /journal 页面（时间线/记录列表/类型化编辑器）"
```

---

### Task 3: API 与反代路由测试

**Files:**
- Create: `frontend/tests/lib/journalApi.test.ts`
- Create: `frontend/tests/lib/journalRoute.test.ts`

- [ ] **Step 1: API 测试**

```ts
import { afterEach, describe, it, expect, vi } from "vitest";
import { fetchEntries, fetchTimeline, createEntry, deleteEntry } from "@/lib/journalApi";

const entryJson = {
  id: 5, type: "BUY_MEMO", stockCode: "600519", stockName: "贵州茅台", tradeId: 10,
  title: "买入茅台", content: "理由", targetPrice: 1800, stopLoss: 1400,
  periodType: null, periodStart: null, periodEnd: null,
  eventDate: "2026-09-02", createdAt: "2026-09-02T08:00:00Z", updatedAt: "2026-09-02T08:00:00Z",
};

afterEach(() => { vi.unstubAllGlobals(); });

describe("journalApi", () => {
  it("fetchEntries 解析记录列表", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [entryJson] }));
    const data = await fetchEntries();
    expect(data[0].title).toBe("买入茅台");
    expect(data[0].stockCode).toBe("600519");
    expect(fetchMockCall()[0]).toBe("/api/journal/entries");
  });

  it("fetchEntries 带类型过滤拼 query", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [] }));
    await fetchEntries("REVIEW");
    expect(fetchMockCall()[0]).toBe("/api/journal/entries?type=REVIEW");
  });

  it("fetchTimeline 解析时间线", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => [{ type: "BUY", date: "2026-08-01", title: "贵州茅台", description: "买入 100 股", stockCode: "600519", stockName: "贵州茅台", refId: 10, refType: "TRADE" }],
    }));
    const events = await fetchTimeline();
    expect(events[0].type).toBe("BUY");
    expect(fetchMockCall()[0]).toBe("/api/journal/timeline");
  });

  it("createEntry 走 POST 并解析", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201, json: async () => entryJson });
    vi.stubGlobal("fetch", fetchMock);
    const cmd = { type: "BUY_MEMO" as const, title: "买入茅台", content: "理由", eventDate: "2026-09-02" };
    const entry = await createEntry(cmd);
    expect(entry.id).toBe(5);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/journal/entries");
    expect(init.method).toBe("POST");
  });

  it("响应不符合 schema 时抛校验错误", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [{ type: "UNKNOWN" }] }));
    await expect(fetchTimeline()).rejects.toThrow();
  });
});

function fetchMockCall(): [string, RequestInit] {
  return (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0] as [string, RequestInit];
}
```

- [ ] **Step 2: 反代路由测试**

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DELETE, GET, POST, PUT } from "@/app/api/journal/[...path]/route";
import type { NextRequest } from "next/server";

function req(url: string, init?: RequestInit): NextRequest {
  return new Request(url, init) as unknown as NextRequest;
}

describe("journal 反代路由", () => {
  const fetchMock = vi.fn();
  beforeEach(() => { vi.stubGlobal("fetch", fetchMock); fetchMock.mockReset(); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("GET 拼对上游路径", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await GET(req("http://localhost:3000/api/journal/entries"), { params: Promise.resolve({ path: ["entries"] }) });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/journal/entries");
  });

  it("POST 透传 body", async () => {
    fetchMock.mockResolvedValue(new Response('{"id":5}', { status: 201 }));
    const body = '{"type":"BUY_MEMO","title":"x","content":"y","eventDate":"2026-09-02"}';
    await POST(new Request("http://localhost:3000/api/journal/entries", { method: "POST", body }), { params: Promise.resolve({ path: ["entries"] }) });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/journal/entries");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(body);
  });

  it("PUT 拼对上游路径", async () => {
    fetchMock.mockResolvedValue(new Response('{"id":5}', { status: 200 }));
    await PUT(new Request("http://localhost:3000/api/journal/entries/5", { method: "PUT", body: '{}' }), { params: Promise.resolve({ path: ["entries", "5"] }) });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/journal/entries/5");
    expect(fetchMock.mock.calls[0][1].method).toBe("PUT");
  });

  it("DELETE 拼对上游路径", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await DELETE(new Request("http://localhost:3000/api/journal/entries/5", { method: "DELETE" }), { params: Promise.resolve({ path: ["entries", "5"] }) });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/journal/entries/5");
  });

  it("透传入站 Cookie 到上游", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await GET(new Request("http://localhost:3000/api/journal/entries", { headers: { Cookie: "JSESSIONID=abc" } }), { params: Promise.resolve({ path: ["entries"] }) });
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>).Cookie).toBe("JSESSIONID=abc");
  });
});
```

- [ ] **Step 3: 跑测试确认通过**

Run: `cd frontend && pnpm vitest run tests/lib/journalApi.test.ts tests/lib/journalRoute.test.ts`
Expected: PASS。

- [ ] **Step 4: Commit**

```bash
git add frontend/tests/lib/journalApi.test.ts frontend/tests/lib/journalRoute.test.ts
git commit -m "test(journal): 前端 API 契约与反代路由测试"
```

---

### Task 4: 组件测试 + Playwright e2e

**Files:**
- Create: `frontend/tests/TimelineView.test.tsx`
- Create: `frontend/e2e/journal.spec.ts`

- [ ] **Step 1: 组件测试**

```tsx
import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import TimelineView from "@/components/journal/TimelineView";

describe("TimelineView", () => {
  it("无事件时显示空态提示", () => {
    render(<TimelineView events={[]} />);
    expect(screen.getByText(/暂无事件/)).toBeInTheDocument();
  });

  it("渲染事件类型与标题", () => {
    render(<TimelineView events={[{
      type: "BUY", date: "2026-08-01", title: "贵州茅台", description: "买入 100 股",
      stockCode: "600519", stockName: "贵州茅台", refId: 10, refType: "TRADE",
    }]} />);
    expect(screen.getByText("贵州茅台")).toBeInTheDocument();
    expect(screen.getByText("买入 100 股")).toBeInTheDocument();
    expect(screen.getByText("买入")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: e2e**

```ts
import { test, expect } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

test.describe("/journal 投资决策记录", () => {
  test.describe.configure({ retries: 0 });
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过配置用例");

  test("登录后访问并创建研究笔记", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("jr"), TEST_PASSWORD);

    await page.getByRole("link", { name: "决策" }).click();
    await expect(page).toHaveURL(/\/journal/, { timeout: 15_000 });
    await expect(page.getByRole("heading", { name: "投资决策记录" })).toBeVisible();
    await expect(page.getByTestId("timeline")).toContainText("暂无事件");

    await page.getByRole("button", { name: "记录" }).click();
    await page.getByRole("button", { name: "研究笔记" }).click();
    await page.getByPlaceholder("标题").fill("白酒行业研究");
    await page.getByPlaceholder("内容（Markdown）").fill("这是研究笔记内容");
    await page.getByRole("button", { name: "保存记录" }).click();

    await expect(page.getByTestId("entry-list").getByText("白酒行业研究")).toBeVisible({ timeout: 15_000 });
  });
});
```

- [ ] **Step 3: 跑测试确认通过**

Run: `cd frontend && pnpm vitest run tests/TimelineView.test.tsx`；e2e：`cd frontend && pnpm test:e2e`（或仅 `pnpm playwright test e2e/journal.spec.ts`）。
Expected: 组件测试 PASS；e2e 在配置了 `ADMIN_USERNAME/ADMIN_PASSWORD` 的环境下 PASS。

- [ ] **Step 4: Commit**

```bash
git add frontend/tests/TimelineView.test.tsx frontend/e2e/journal.spec.ts
git commit -m "test(journal): 时间线组件测试与 e2e 用例"
```

---

## P3 完成验证

```bash
cd frontend && pnpm lint                       # eslint 通过（组件无直接 fetch）
cd frontend && pnpm vitest run                 # 全量单测（含 journalApi/journalRoute/TimelineView）
cd frontend && pnpm test:e2e                   # Playwright e2e（配置管理员种子后）
```

确认：`/journal` 未登录跳转登录（`RequireAuth`）；反代透传 Cookie（`relay`）；zod 契约在边界拦截 schema drift。

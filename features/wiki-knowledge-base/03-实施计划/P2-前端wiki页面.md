# P2 · 前端知识库页面实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/wiki` 页面上线（四 tab：原则纪律/读书笔记/概念速查/研究笔记）：API 客户端 + 反代路由 + MarkdownView 共享组件抽取 + WikiBoard/NotePanel/RulePanel 组件 + 导航链接。

**Architecture:** 照 journal 前端模式：每域一个 `lib/wikiApi.ts`（`lib/http.ts` `request<T>` + zod 出参校验）+ `app/api/wiki/[...path]/route.ts` relay 反代；页面 server component（`RequireAuth`）+ 客户端 `WikiBoard`（tab 状态机 + requestSeqRef 防过期响应）；`?tab=` 深链由 server 侧 `searchParams`（Next 15 Promise）解析为 `initialTab` prop。Markdown 渲染配置从 chat `ThreadArea` 抽成 `components/shared/MarkdownView.tsx` 共用。

**Tech Stack:** Next.js 15.5 App Router / React 19 / Tailwind 4 / zod / react-markdown + remark-gfm（已有依赖，零新增）/ vitest + testing-library

**Spec:** `features/wiki-knowledge-base/01-需求规格/需求规格说明.md`、`features/wiki-knowledge-base/02-设计规格/设计规格说明.md` §4、§5（代码样例均已对照仓库真实代码核实）

## Global Constraints

- 不新增任何 npm 依赖（react-markdown/remark-gfm/highlight.js 均已有）
- API 契约与 P1 `WikiController` 一致：`/api/wiki/entries[?type=]`、`/api/wiki/rules`；错误体 `{code, message}`
- 样式沿用仓库 CSS 变量惯例：错误 `text-[color:var(--color-down)]`、面板 `rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70`、输入 `rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm`
- 比例阈值 UI 显示 `%`、存储 0~1；倍数直接数值
- `make test-frontend`（lint + vitest）全绿；chat 既有测试不回归（MarkdownView 抽取）

---

### Task 1: 类型、schema 与 wikiApi 客户端

**Files:**
- Modify: `frontend/lib/types.ts`（文件尾部追加 wiki 段）
- Modify: `frontend/lib/schemas.ts`（文件尾部追加 wiki 段）
- Create: `frontend/lib/wikiApi.ts`
- Test: `frontend/tests/lib/wikiApi.test.ts`

**Interfaces:**
- Consumes: P1 端点契约
- Produces: `WikiEntryType` / `PrincipleMetric` / `WikiEntryView` / `PrincipleRuleView` 类型；`fetchWikiEntries(type?)` / `createWikiEntry(cmd)` / `updateWikiEntry(id, cmd)` / `deleteWikiEntry(id)` / `fetchRules()` / `createRule(cmd)` / `updateRule(id, cmd)` / `deleteRule(id)`；`WIKI_ENTRY_TYPE_LABELS` / `PRINCIPLE_METRIC_LABELS` / `RATIO_METRICS`。Task 4/5/6 与 P3 组件依赖。

- [ ] **Step 1: 写失败测试**（照 `journalApi.test.ts` 的 fetch stub 模式）

```ts
import { afterEach, describe, it, expect, vi } from "vitest";
import {
  createRule, createWikiEntry, fetchRules, fetchWikiEntries, PRINCIPLE_METRIC_LABELS, RATIO_METRICS,
} from "@/lib/wikiApi";

const entryJson = {
  id: 5, type: "BOOK_NOTE", title: "《聪明的投资者》", content: "## 核心观点",
  category: null, industryCode: null, createdAt: "2026-09-22T08:00:00Z", updatedAt: "2026-09-22T08:00:00Z",
};
const ruleJson = {
  id: 9, metric: "SINGLE_POSITION_RATIO", threshold: 0.2, enabled: true,
  description: null, createdAt: "2026-09-22T08:00:00Z", updatedAt: "2026-09-22T08:00:00Z",
};

afterEach(() => { vi.unstubAllGlobals(); });

describe("wikiApi", () => {
  it("fetchWikiEntries 解析条目列表", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [entryJson] }));
    const data = await fetchWikiEntries();
    expect(data[0].title).toBe("《聪明的投资者》");
    expect(fetchMockCall()[0]).toBe("/api/wiki/entries");
  });

  it("fetchWikiEntries 带类型过滤拼 query", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [] }));
    await fetchWikiEntries("CONCEPT");
    expect(fetchMockCall()[0]).toBe("/api/wiki/entries?type=CONCEPT");
  });

  it("createWikiEntry 走 POST 并解析", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201, json: async () => entryJson });
    vi.stubGlobal("fetch", fetchMock);
    const entry = await createWikiEntry({ type: "BOOK_NOTE", title: "《聪明的投资者》", content: "## 核心观点" });
    expect(entry.id).toBe(5);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/wiki/entries");
    expect(init.method).toBe("POST");
  });

  it("fetchRules 解析规则列表", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [ruleJson] }));
    const rules = await fetchRules();
    expect(rules[0].metric).toBe("SINGLE_POSITION_RATIO");
    expect(rules[0].threshold).toBe(0.2);
  });

  it("createRule 走 POST 并解析", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201, json: async () => ruleJson });
    vi.stubGlobal("fetch", fetchMock);
    const rule = await createRule({ metric: "SINGLE_POSITION_RATIO", threshold: 0.2, enabled: true });
    expect(rule.id).toBe(9);
    expect(fetchMock.mock.calls[0][1].method).toBe("POST");
  });

  it("响应不符合 schema 时抛校验错误", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [{ metric: "UNKNOWN" }] }));
    await expect(fetchRules()).rejects.toThrow();
  });

  it("标签与比例指标集合常量", () => {
    expect(PRINCIPLE_METRIC_LABELS.SINGLE_POSITION_RATIO).toBe("单票仓位上限");
    expect(RATIO_METRICS.has("SINGLE_POSITION_RATIO")).toBe(true);
    expect(RATIO_METRICS.has("STOCK_PE_MAX")).toBe(false);
  });
});

function fetchMockCall(): [string, RequestInit] {
  return (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0] as [string, RequestInit];
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd frontend && pnpm test -- tests/lib/wikiApi.test.ts`
Expected: FAIL（`@/lib/wikiApi` 不存在）

- [ ] **Step 3: 最小实现**

`frontend/lib/types.ts` 尾部追加：

```ts
// —— 投资知识库（/api/wiki/**，与后端 WikiController 的 View 对齐）——

export type WikiEntryType = "BOOK_NOTE" | "CONCEPT" | "RESEARCH_NOTE";
export type PrincipleMetric =
  | "SINGLE_POSITION_RATIO"
  | "INDUSTRY_POSITION_RATIO"
  | "STOCK_PE_MAX"
  | "STOCK_PB_MAX";

export interface WikiEntryView {
  id: number;
  type: WikiEntryType;
  title: string;
  content: string;
  category: string | null;
  industryCode: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PrincipleRuleView {
  id: number;
  metric: PrincipleMetric;
  threshold: number;
  enabled: boolean;
  description: string | null;
  createdAt: string;
  updatedAt: string;
}
```

`frontend/lib/schemas.ts` 尾部追加：

```ts
// —— 投资知识库（/api/wiki/**，与后端 WikiController 的 View 对齐）——

export const WikiEntryTypeSchema = z.enum(["BOOK_NOTE", "CONCEPT", "RESEARCH_NOTE"]);
export const PrincipleMetricSchema = z.enum([
  "SINGLE_POSITION_RATIO", "INDUSTRY_POSITION_RATIO", "STOCK_PE_MAX", "STOCK_PB_MAX",
]);
export const WikiEntryViewSchema = z.object({
  id: z.number(),
  type: WikiEntryTypeSchema,
  title: z.string(),
  content: z.string(),
  category: z.string().nullable(),
  industryCode: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export const PrincipleRuleViewSchema = z.object({
  id: z.number(),
  metric: PrincipleMetricSchema,
  threshold: z.number(),
  enabled: z.boolean(),
  description: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
```

`frontend/lib/wikiApi.ts`：

```ts
import { z } from "zod";
import { PrincipleRuleViewSchema, WikiEntryViewSchema } from "./schemas";
import type { PrincipleMetric, PrincipleRuleView, WikiEntryType, WikiEntryView } from "./types";
import { request } from "./http";

export interface WikiEntryInput {
  type: WikiEntryType;
  title: string;
  content: string;
  category?: string | null;
  industryCode?: string | null;
}

export interface PrincipleRuleInput {
  metric: PrincipleMetric;
  threshold: number;
  enabled: boolean;
  description?: string | null;
}

export const WIKI_ENTRY_TYPE_LABELS: Record<WikiEntryType, string> = {
  BOOK_NOTE: "读书笔记", CONCEPT: "概念速查", RESEARCH_NOTE: "研究笔记",
};

export const PRINCIPLE_METRIC_LABELS: Record<PrincipleMetric, string> = {
  SINGLE_POSITION_RATIO: "单票仓位上限",
  INDUSTRY_POSITION_RATIO: "单行业仓位上限",
  STOCK_PE_MAX: "个股PE上限",
  STOCK_PB_MAX: "个股PB上限",
};

/** 比例类指标（阈值 UI 显示 %、存储 0~1）；与后端 PrincipleMetric.isRatio() 同口径 */
export const RATIO_METRICS: ReadonlySet<PrincipleMetric> = new Set([
  "SINGLE_POSITION_RATIO", "INDUSTRY_POSITION_RATIO",
]);

export const fetchWikiEntries = (type?: WikiEntryType) =>
  request<WikiEntryView[]>(`/api/wiki/entries${type ? `?type=${type}` : ""}`, "GET", undefined, z.array(WikiEntryViewSchema));
export const createWikiEntry = (cmd: WikiEntryInput) =>
  request<WikiEntryView>("/api/wiki/entries", "POST", cmd, WikiEntryViewSchema);
export const updateWikiEntry = (id: number, cmd: WikiEntryInput) =>
  request<WikiEntryView>(`/api/wiki/entries/${id}`, "PUT", cmd, WikiEntryViewSchema);
export const deleteWikiEntry = (id: number) =>
  request<void>(`/api/wiki/entries/${id}`, "DELETE");

export const fetchRules = () =>
  request<PrincipleRuleView[]>("/api/wiki/rules", "GET", undefined, z.array(PrincipleRuleViewSchema));
export const createRule = (cmd: PrincipleRuleInput) =>
  request<PrincipleRuleView>("/api/wiki/rules", "POST", cmd, PrincipleRuleViewSchema);
export const updateRule = (id: number, cmd: PrincipleRuleInput) =>
  request<PrincipleRuleView>(`/api/wiki/rules/${id}`, "PUT", cmd, PrincipleRuleViewSchema);
export const deleteRule = (id: number) =>
  request<void>(`/api/wiki/rules/${id}`, "DELETE");
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd frontend && pnpm test -- tests/lib/wikiApi.test.ts`
Expected: PASS（7 tests）

- [ ] **Step 5: 提交**

```bash
git add frontend/lib/types.ts frontend/lib/schemas.ts frontend/lib/wikiApi.ts frontend/tests/lib/wikiApi.test.ts
git commit -m "feat(wiki): 前端 API 客户端与类型/schema（含比例指标口径常量）"
```

---

### Task 2: 反代路由 app/api/wiki

**Files:**
- Create: `frontend/app/api/wiki/[...path]/route.ts`
- Test: `frontend/tests/lib/wikiRoute.test.ts`

**Interfaces:**
- Consumes: `lib/proxy.ts` 的 `relay`（已存在）
- Produces: 同源 `/api/wiki/**` → `BACKEND_URL`（默认 `http://localhost:8080`）反代，透传 Cookie/body/query。

- [ ] **Step 1: 写失败测试**（照 `journalRoute.test.ts` 逐字同款，路径换 wiki）

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DELETE, GET, POST, PUT } from "@/app/api/wiki/[...path]/route";
import type { NextRequest } from "next/server";

function req(url: string, init?: RequestInit): NextRequest {
  return new Request(url, init) as unknown as NextRequest;
}

describe("wiki 反代路由", () => {
  const fetchMock = vi.fn();
  beforeEach(() => { vi.stubGlobal("fetch", fetchMock); fetchMock.mockReset(); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("GET 拼对上游路径", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await GET(req("http://localhost:3000/api/wiki/entries"), { params: Promise.resolve({ path: ["entries"] }) });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/wiki/entries");
  });

  it("GET 透传 query string 到上游", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await GET(new Request("http://localhost:3000/api/wiki/entries?type=CONCEPT"), { params: Promise.resolve({ path: ["entries"] }) });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/wiki/entries?type=CONCEPT");
  });

  it("POST 透传 body", async () => {
    fetchMock.mockResolvedValue(new Response('{"id":5}', { status: 201 }));
    const body = '{"type":"BOOK_NOTE","title":"x","content":"y"}';
    await POST(new Request("http://localhost:3000/api/wiki/entries", { method: "POST", body }), { params: Promise.resolve({ path: ["entries"] }) });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/wiki/entries");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(body);
  });

  it("PUT 拼对上游路径", async () => {
    fetchMock.mockResolvedValue(new Response('{"id":5}', { status: 200 }));
    await PUT(new Request("http://localhost:3000/api/wiki/entries/5", { method: "PUT", body: "{}" }), { params: Promise.resolve({ path: ["entries", "5"] }) });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/wiki/entries/5");
    expect(fetchMock.mock.calls[0][1].method).toBe("PUT");
  });

  it("DELETE 拼对上游路径", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await DELETE(new Request("http://localhost:3000/api/wiki/rules/9", { method: "DELETE" }), { params: Promise.resolve({ path: ["rules", "9"] }) });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/wiki/rules/9");
  });

  it("透传入站 Cookie 到上游", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await GET(new Request("http://localhost:3000/api/wiki/rules", { headers: { Cookie: "JSESSIONID=abc" } }), { params: Promise.resolve({ path: ["rules"] }) });
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>).Cookie).toBe("JSESSIONID=abc");
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd frontend && pnpm test -- tests/lib/wikiRoute.test.ts`
Expected: FAIL（route 模块不存在）

- [ ] **Step 3: 最小实现**（`app/api/journal/[...path]/route.ts` 逐字同款换前缀）

```ts
import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

async function resolve(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  const u = new URL(req.url);
  return "/api/wiki" + (path.length ? "/" + path.join("/") : "") + u.search;
}

export async function GET(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(req, ctx), "GET", req);
}
export async function POST(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(req, ctx), "POST", req, await req.text());
}
export async function PUT(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(req, ctx), "PUT", req, await req.text());
}
export async function DELETE(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(req, ctx), "DELETE", req);
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd frontend && pnpm test -- tests/lib/wikiRoute.test.ts`
Expected: PASS（6 tests）

- [ ] **Step 5: 提交**

```bash
git add frontend/app/api/wiki/ frontend/tests/lib/wikiRoute.test.ts
git commit -m "feat(wiki): /api/wiki 反代路由（relay 透传）"
```

---

### Task 3: MarkdownView 共享组件抽取（chat 复用）

**Files:**
- Create: `frontend/components/shared/MarkdownView.tsx`
- Modify: `frontend/components/chat/ThreadArea.tsx`（242~292 行内联块替换为共享组件）
- Test: `frontend/tests/shared/MarkdownView.test.tsx`

**Interfaces:**
- Consumes: `react-markdown` / `remark-gfm` / `components/chat/CodeHighlight` 的 `CodeBlock`/`InlineCode`（均已有）
- Produces: `export default MarkdownView({ content, className? }: { content: string; className?: string })`，默认 className `md-body text-[14px] leading-relaxed text-[color:var(--color-ink)]`。Task 5 的 NoteEditor/NotePanel 与 P3 弹窗依赖；chat 行为不变。

- [ ] **Step 1: 写失败测试**

```tsx
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import MarkdownView from "@/components/shared/MarkdownView";

afterEach(() => cleanup());

describe("MarkdownView", () => {
  it("渲染标题/列表 Markdown", () => {
    render(<MarkdownView content={"## 核心观点\n- 市场先生"} />);
    expect(screen.getByRole("heading", { name: "核心观点" })).toBeTruthy();
    expect(screen.getByText("市场先生")).toBeTruthy();
  });

  it("https 链接放行，http 明文外域拦截（FR-6 同款）", () => {
    render(<MarkdownView content={"[安全](https://example.com) [明文](http://example.com)"} />);
    expect(screen.getByText("安全").closest("a")?.getAttribute("href")).toBe("https://example.com");
    expect(screen.getByText("明文").closest("a")?.getAttribute("href")).toBe("");
  });

  it("行内代码渲染 InlineCode", () => {
    render(<MarkdownView content={"`PE` 口径"} />);
    expect(screen.getByText("PE")).toBeTruthy();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd frontend && pnpm test -- tests/shared/MarkdownView.test.tsx`
Expected: FAIL（组件不存在）

- [ ] **Step 3: 最小实现**

`frontend/components/shared/MarkdownView.tsx`（配置逐字取自 `ThreadArea.tsx:242-292` 现行内联块）：

```tsx
import { memo } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { CodeBlock, InlineCode } from "@/components/chat/CodeHighlight";

/** chat/wiki 共用的 Markdown 渲染配置（自 ThreadArea 抽出，行为不变）。 */
function MarkdownView({ content, className = "md-body text-[14px] leading-relaxed text-[color:var(--color-ink)]" }: {
  content: string;
  className?: string;
}) {
  return (
    <div className={className}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        urlTransform={(url) => {
          // 默认 transform 已拦 javascript:/data:；再拦 http:（明文外域）。
          // 同源相对路径与 https 放行。
          if (url.startsWith("/") || url.startsWith("https://")) return url;
          return "";
        }}
        components={{
          pre: (p) => <>{p.children}</>,
          code: ({ className, children }) => {
            // 有语言类名，或含换行（无语言围栏代码块）均按块渲染
            const isBlock =
              /language-[\w-]+/.test(className ?? "") ||
              String(children ?? "").includes("\n");
            return isBlock ? (
              <CodeBlock className={className}>{children}</CodeBlock>
            ) : (
              <InlineCode>{children}</InlineCode>
            );
          },
          img: ({ src, alt, title }) => (
            // 外域 URL 来源不可枚举，以 https 门控 + no-referrer + 尺寸约束兜底。
            // eslint-disable-next-line @next/next/no-img-element
            <img
              src={src}
              alt={alt ?? ""}
              title={title}
              loading="lazy"
              decoding="async"
              referrerPolicy="no-referrer"
              className="my-2 max-h-[420px] max-w-full rounded-md border border-[color:var(--color-line-soft)]"
            />
          ),
          a: ({ href, children }) => (
            <a
              href={href}
              target="_blank"
              rel="noopener noreferrer"
              className="underline decoration-[color:var(--color-ink-faint)] underline-offset-2"
            >
              {children}
            </a>
          ),
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}

export default memo(MarkdownView);
```

`ThreadArea.tsx`：把 `{content && (<div className="md-body ...">…</ReactMarkdown></div>)}` 整块（242~293 行）替换为：

```tsx
        {content && <MarkdownView content={content} />}
```

并加 import `import MarkdownView from "@/components/shared/MarkdownView";`；若 `ReactMarkdown`/`remarkGfm`/`CodeBlock`/`InlineCode` 因此不再被本文件使用则移除对应 import（以 `pnpm lint` 无未用 import 为准）。

- [ ] **Step 4: 跑测试确认通过 + chat 回归**

Run: `cd frontend && pnpm test -- tests/shared/MarkdownView.test.tsx tests/chat`
Expected: PASS（新 3 tests + chat 既有测试无回归）

- [ ] **Step 5: 提交**

```bash
git add frontend/components/shared/MarkdownView.tsx frontend/components/chat/ThreadArea.tsx frontend/tests/shared/MarkdownView.test.tsx
git commit -m "refactor(shared): MarkdownView 共享组件抽取（chat 配置原样迁移）"
```

---

### Task 4: WikiBoard 骨架、/wiki 页面与导航

**Files:**
- Create: `frontend/components/wiki/WikiBoard.tsx`
- Create: `frontend/app/wiki/page.tsx`
- Modify: `frontend/app/layout.tsx`（导航「行业」链接后加「知识库」）
- Test: `frontend/tests/wiki/WikiBoard.test.tsx`

**Interfaces:**
- Consumes: Task 1 的 `fetchWikiEntries`/`fetchRules` 与类型
- Produces: `export default WikiBoard({ initialTab }: { initialTab?: WikiTab })`；`export type WikiTab = "principle" | "book" | "concept" | "research"`；tab 切换时按 tab 拉取对应类型条目（principle 只拉 rules）；`data-testid`：`wiki-tab-<tab>`、`wiki-board`。Task 5/6 的面板组件挂在其下。

- [ ] **Step 1: 写失败测试**

```tsx
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import WikiBoard from "@/components/wiki/WikiBoard";
import * as wikiApi from "@/lib/wikiApi";

afterEach(() => cleanup());

describe("WikiBoard", () => {
  beforeEach(() => {
    vi.spyOn(wikiApi, "fetchWikiEntries").mockResolvedValue([]);
    vi.spyOn(wikiApi, "fetchRules").mockResolvedValue([]);
  });

  it("渲染四 tab 与标题", async () => {
    render(<WikiBoard />);
    expect(screen.getByRole("heading", { name: "投资知识库" })).toBeTruthy();
    expect(screen.getByTestId("wiki-tab-principle")).toBeTruthy();
    expect(screen.getByTestId("wiki-tab-book")).toBeTruthy();
    expect(screen.getByTestId("wiki-tab-concept")).toBeTruthy();
    expect(screen.getByTestId("wiki-tab-research")).toBeTruthy();
    await waitFor(() => expect(wikiApi.fetchRules).toHaveBeenCalled());
  });

  it("切到概念 tab 按 CONCEPT 拉取", async () => {
    render(<WikiBoard />);
    fireEvent.click(screen.getByTestId("wiki-tab-concept"));
    await waitFor(() => expect(wikiApi.fetchWikiEntries).toHaveBeenCalledWith("CONCEPT"));
  });

  it("切到读书笔记 tab 按 BOOK_NOTE 拉取", async () => {
    render(<WikiBoard />);
    fireEvent.click(screen.getByTestId("wiki-tab-book"));
    await waitFor(() => expect(wikiApi.fetchWikiEntries).toHaveBeenCalledWith("BOOK_NOTE"));
  });

  it("initialTab 生效（行业页深链 research）", async () => {
    render(<WikiBoard initialTab="research" />);
    await waitFor(() => expect(wikiApi.fetchWikiEntries).toHaveBeenCalledWith("RESEARCH_NOTE"));
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd frontend && pnpm test -- tests/wiki/WikiBoard.test.tsx`
Expected: FAIL（组件不存在）

- [ ] **Step 3: 最小实现**

`frontend/components/wiki/WikiBoard.tsx`（照 `JournalBoard` 的 tab + requestSeqRef 范式；本任务先渲染占位面板，Task 5/6 替换）：

```tsx
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
```

> 本任务先创建**最小占位**的 `NotePanel.tsx` / `RulePanel.tsx`（`export default function X() { return null; }`，带正确 props 类型）让 WikiBoard 编译通过；Task 5/6 分别实现真体。测试只断言 tab 切换与拉取参数，不受占位影响。

`frontend/app/wiki/page.tsx`：

```tsx
import RequireAuth from "@/components/auth/RequireAuth";
import WikiBoard, { type WikiTab } from "@/components/wiki/WikiBoard";

const TAB_KEYS: readonly string[] = ["principle", "book", "concept", "research"];

export default async function WikiPage({ searchParams }: { searchParams: Promise<{ tab?: string }> }) {
  const { tab } = await searchParams; // Next 15：searchParams 是 Promise（同 params）
  const initialTab = TAB_KEYS.includes(tab ?? "") ? (tab as WikiTab) : "principle";
  return (
    <RequireAuth>
      <WikiBoard initialTab={initialTab} />
    </RequireAuth>
  );
}
```

`frontend/app/layout.tsx`：在「行业」Link（`href="/industry"`）之后加同款 Link：

```tsx
              <Link
                href="/wiki"
                className="rounded-md px-3 py-1.5 text-[color:var(--color-ink-dim)] transition-colors hover:bg-[color:var(--color-panel)] hover:text-[color:var(--color-ink)]"
              >
                知识库
              </Link>
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd frontend && pnpm test -- tests/wiki/WikiBoard.test.tsx`
Expected: PASS（4 tests）

- [ ] **Step 5: 提交**

```bash
git add frontend/components/wiki/ frontend/app/wiki/ frontend/app/layout.tsx frontend/tests/wiki/WikiBoard.test.tsx
git commit -m "feat(wiki): /wiki 页面骨架（四 tab + ?tab= 深链）与导航"
```

---

### Task 5: NotePanel 与 NoteEditor（列表/编辑/预览/渲染）

**Files:**
- Create: `frontend/components/wiki/NoteEditor.tsx`
- Modify: `frontend/components/wiki/NotePanel.tsx`（占位换真体）
- Test: `frontend/tests/wiki/NotePanel.test.tsx`

**Interfaces:**
- Consumes: Task 1 的 `createWikiEntry`/`updateWikiEntry`/`deleteWikiEntry`、Task 3 的 `MarkdownView`
- Produces: `NotePanel({ type, entries, onChanged })` 与 `NoteEditor({ type, editing, onSaved, onCancel })`；`data-testid`：`wiki-note-list`、`wiki-note-<id>`（条目标题按钮）、`wiki-note-title`/`wiki-note-content`/`wiki-note-category`/`wiki-note-preview-toggle`/`wiki-note-save`。P3 行业弹窗复用 NoteEditor 的交互模式但不直接复用组件（弹窗独立实现）。

- [ ] **Step 1: 写失败测试**

```tsx
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import NotePanel from "@/components/wiki/NotePanel";
import * as wikiApi from "@/lib/wikiApi";
import type { WikiEntryView } from "@/lib/types";

afterEach(() => cleanup());

const entries: WikiEntryView[] = [
  { id: 5, type: "BOOK_NOTE", title: "《聪明的投资者》", content: "## 核心观点\n- 市场先生",
    category: null, industryCode: null, createdAt: "2026-09-22T08:00:00Z", updatedAt: "2026-09-22T08:00:00Z" },
  { id: 6, type: "CONCEPT", title: "护城河", content: "结构性优势",
    category: "质量", industryCode: null, createdAt: "2026-09-22T08:00:00Z", updatedAt: "2026-09-22T08:00:00Z" },
];

describe("NotePanel", () => {
  it("列表渲染条目标题与概念分类", () => {
    render(<NotePanel type="CONCEPT" entries={entries} onChanged={() => {}} />);
    expect(screen.getByTestId("wiki-note-list")).toBeTruthy();
    expect(screen.getByTestId("wiki-note-5").textContent).toContain("《聪明的投资者》");
    expect(screen.getByTestId("wiki-note-6").textContent).toContain("护城河");
    expect(screen.getByTestId("wiki-note-6").textContent).toContain("质量");
  });

  it("点标题展开 Markdown 渲染详情（h2）", () => {
    render(<NotePanel type="BOOK_NOTE" entries={entries} onChanged={() => {}} />);
    fireEvent.click(screen.getByTestId("wiki-note-5"));
    expect(screen.getByRole("heading", { name: "核心观点" })).toBeTruthy();
    fireEvent.click(screen.getByTestId("wiki-note-5")); // 再点收起
    expect(screen.queryByRole("heading", { name: "核心观点" })).toBeNull();
  });

  it("编辑器：预览切换渲染、保存校验与调用", async () => {
    const createSpy = vi.spyOn(wikiApi, "createWikiEntry").mockResolvedValue(entries[0]);
    render(<NotePanel type="BOOK_NOTE" entries={[]} onChanged={() => {}} />);
    fireEvent.change(screen.getByTestId("wiki-note-title"), { target: { value: "新笔记" } });
    fireEvent.change(screen.getByTestId("wiki-note-content"), { target: { value: "## 新内容" } });
    // 预览切换
    fireEvent.click(screen.getByTestId("wiki-note-preview-toggle"));
    expect(screen.getByRole("heading", { name: "新内容" })).toBeTruthy();
    fireEvent.click(screen.getByTestId("wiki-note-preview-toggle")); // 切回编辑
    fireEvent.click(screen.getByTestId("wiki-note-save"));
    await waitFor(() => expect(createSpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: "BOOK_NOTE", title: "新笔记", content: "## 新内容" })));
  });

  it("空标题保存被前端拦截，不调 API", async () => {
    const createSpy = vi.spyOn(wikiApi, "createWikiEntry").mockResolvedValue(entries[0]);
    render(<NotePanel type="BOOK_NOTE" entries={[]} onChanged={() => {}} />);
    fireEvent.change(screen.getByTestId("wiki-note-content"), { target: { value: "只有内容" } });
    fireEvent.click(screen.getByTestId("wiki-note-save"));
    expect(createSpy).not.toHaveBeenCalled();
  });

  it("概念类型编辑器含分类输入", () => {
    render(<NotePanel type="CONCEPT" entries={[]} onChanged={() => {}} />);
    expect(screen.getByTestId("wiki-note-category")).toBeTruthy();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd frontend && pnpm test -- tests/wiki/NotePanel.test.tsx`
Expected: FAIL（NotePanel 是占位 null）

- [ ] **Step 3: 最小实现**

`frontend/components/wiki/NoteEditor.tsx`：

```tsx
"use client";

import { useState } from "react";
import MarkdownView from "@/components/shared/MarkdownView";
import { createWikiEntry, updateWikiEntry } from "@/lib/wikiApi";
import type { WikiEntryType, WikiEntryView } from "@/lib/types";

export default function NoteEditor({ type, editing, onSaved, onCancel }: {
  type: WikiEntryType;
  editing: WikiEntryView | null;
  onSaved: () => void;
  onCancel: () => void;
}) {
  const [title, setTitle] = useState(editing?.title ?? "");
  const [category, setCategory] = useState(editing?.category ?? "");
  const [content, setContent] = useState(editing?.content ?? "");
  const [preview, setPreview] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const save = async () => {
    setError(null);
    const trimmed = title.trim();
    if (!trimmed) { setError("标题不能为空"); return; }
    if (!content.trim()) { setError("内容不能为空"); return; }
    const cmd = {
      type, title: trimmed, content,
      category: type === "CONCEPT" ? (category.trim() || null) : (editing?.category ?? null),
      industryCode: editing?.industryCode ?? null, // 研究结论来源行业保留，手工创建为 null
    };
    try {
      if (editing) await updateWikiEntry(editing.id, cmd);
      else await createWikiEntry(cmd);
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : "保存失败");
    }
  };

  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-3">
      <div className="font-[family-name:var(--font-display)] text-[15px]">{editing ? "编辑条目" : "新建条目"}</div>
      <input data-testid="wiki-note-title" className="w-full rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm"
        placeholder="标题" value={title} onChange={(e) => setTitle(e.target.value)} />
      {type === "CONCEPT" && (
        <input data-testid="wiki-note-category" className="w-full rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm"
          placeholder="分类（如：估值/质量/行为）" value={category} onChange={(e) => setCategory(e.target.value)} />
      )}
      <div className="flex items-center justify-between">
        <span className="text-xs text-[color:var(--color-ink-faint)]">Markdown</span>
        <button data-testid="wiki-note-preview-toggle" type="button"
          className="rounded-md border border-[color:var(--color-line)] px-2 py-1 text-xs"
          onClick={() => setPreview(!preview)}>
          {preview ? "编辑" : "预览"}
        </button>
      </div>
      {preview ? (
        <div className="min-h-32 rounded-md border border-[color:var(--color-line-soft)] p-3">
          {content.trim() ? <MarkdownView content={content} /> : <span className="text-sm text-[color:var(--color-ink-faint)]">暂无内容</span>}
        </div>
      ) : (
        <textarea data-testid="wiki-note-content" rows={8}
          className="w-full rounded-md border border-[color:var(--color-line)] px-3 py-2 text-sm min-h-32"
          placeholder="内容（Markdown）" value={content} onChange={(e) => setContent(e.target.value)} />
      )}
      {error && <div className="text-sm text-[color:var(--color-down)]">{error}</div>}
      <div className="flex gap-2">
        <button data-testid="wiki-note-save" type="button"
          className="rounded-md px-4 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]"
          onClick={save}>
          {editing ? "保存修改" : "保存"}
        </button>
        {editing && (
          <button type="button" className="rounded-md px-4 py-1.5 text-sm border border-[color:var(--color-line)]"
            onClick={onCancel}>
            取消
          </button>
        )}
      </div>
    </div>
  );
}
```

`frontend/components/wiki/NotePanel.tsx`：

```tsx
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
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd frontend && pnpm test -- tests/wiki/NotePanel.test.tsx tests/wiki/WikiBoard.test.tsx`
Expected: PASS（5 + 4 tests）

- [ ] **Step 5: 提交**

```bash
git add frontend/components/wiki/NoteEditor.tsx frontend/components/wiki/NotePanel.tsx frontend/tests/wiki/NotePanel.test.tsx
git commit -m "feat(wiki): 内容面板（列表/编辑器/预览切换/详情渲染）"
```

---

### Task 6: RulePanel（规则表单/列表/启停/% 换算）

**Files:**
- Modify: `frontend/components/wiki/RulePanel.tsx`（占位换真体）
- Test: `frontend/tests/wiki/RulePanel.test.tsx`

**Interfaces:**
- Consumes: Task 1 的 `createRule`/`updateRule`/`deleteRule`/`PRINCIPLE_METRIC_LABELS`/`RATIO_METRICS`
- Produces: `RulePanel({ rules, onChanged })`；`data-testid`：`wiki-rule-list`、`wiki-rule-<id>`、`wiki-rule-metric`（下拉，编辑态锁定）、`wiki-rule-threshold`（数值输入）、`wiki-rule-description`、`wiki-rule-save`、`wiki-rule-toggle-<id>`（启停）、`wiki-rule-edit-<id>`、`wiki-rule-delete-<id>`。e2e（P3）依赖这些 testid/文案。

- [ ] **Step 1: 写失败测试**

```tsx
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import RulePanel from "@/components/wiki/RulePanel";
import * as wikiApi from "@/lib/wikiApi";
import type { PrincipleRuleView } from "@/lib/types";

afterEach(() => cleanup());

const rules: PrincipleRuleView[] = [
  { id: 9, metric: "SINGLE_POSITION_RATIO", threshold: 0.2, enabled: true,
    description: "单票≤20%", createdAt: "2026-09-22T08:00:00Z", updatedAt: "2026-09-22T08:00:00Z" },
  { id: 10, metric: "STOCK_PE_MAX", threshold: 40, enabled: false,
    description: null, createdAt: "2026-09-22T08:00:00Z", updatedAt: "2026-09-22T08:00:00Z" },
];

describe("RulePanel", () => {
  it("列表渲染指标中文名、按单位格式化阈值与说明", () => {
    render(<RulePanel rules={rules} onChanged={() => {}} />);
    const first = screen.getByTestId("wiki-rule-9").textContent ?? "";
    expect(first).toContain("单票仓位上限");
    expect(first).toContain("20%"); // 0.2 → 20%
    expect(first).toContain("单票≤20%");
    const second = screen.getByTestId("wiki-rule-10").textContent ?? "";
    expect(second).toContain("个股PE上限");
    expect(second).toContain("40"); // 倍数原样
  });

  it("比例指标输入 % 存 0~1（20 → 0.2）", async () => {
    const createSpy = vi.spyOn(wikiApi, "createRule").mockResolvedValue(rules[0]);
    render(<RulePanel rules={[]} onChanged={() => {}} />);
    fireEvent.change(screen.getByTestId("wiki-rule-metric"), { target: { value: "SINGLE_POSITION_RATIO" } });
    fireEvent.change(screen.getByTestId("wiki-rule-threshold"), { target: { value: "20" } });
    fireEvent.click(screen.getByTestId("wiki-rule-save"));
    await waitFor(() => expect(createSpy).toHaveBeenCalledWith(
      expect.objectContaining({ metric: "SINGLE_POSITION_RATIO", threshold: 0.2, enabled: true })));
  });

  it("倍数指标直接数值（40 → 40）", async () => {
    const createSpy = vi.spyOn(wikiApi, "createRule").mockResolvedValue(rules[1]);
    render(<RulePanel rules={[]} onChanged={() => {}} />);
    fireEvent.change(screen.getByTestId("wiki-rule-metric"), { target: { value: "STOCK_PE_MAX" } });
    fireEvent.change(screen.getByTestId("wiki-rule-threshold"), { target: { value: "40" } });
    fireEvent.click(screen.getByTestId("wiki-rule-save"));
    await waitFor(() => expect(createSpy).toHaveBeenCalledWith(
      expect.objectContaining({ metric: "STOCK_PE_MAX", threshold: 40 })));
  });

  it("已配置指标下拉置灰（其余可选）", () => {
    render(<RulePanel rules={rules} onChanged={() => {}} />);
    const select = screen.getByTestId("wiki-rule-metric") as HTMLSelectElement;
    const option = [...select.options].find((o) => o.value === "SINGLE_POSITION_RATIO");
    expect(option?.disabled).toBe(true);
    const other = [...select.options].find((o) => o.value === "STOCK_PB_MAX");
    expect(other?.disabled).toBe(false);
  });

  it("启停切换调 updateRule", async () => {
    const updateSpy = vi.spyOn(wikiApi, "updateRule").mockResolvedValue(rules[0]);
    render(<RulePanel rules={rules} onChanged={() => {}} />);
    fireEvent.click(screen.getByTestId("wiki-rule-toggle-9"));
    await waitFor(() => expect(updateSpy).toHaveBeenCalledWith(9,
      expect.objectContaining({ threshold: 0.2, enabled: false })));
  });

  it("DUPLICATE_METRIC 错误行内展示", async () => {
    const createSpy = vi.spyOn(wikiApi, "createRule")
      .mockRejectedValue(new Error("该指标已有规则，请直接编辑既有规则"));
    render(<RulePanel rules={[]} onChanged={() => {}} />);
    fireEvent.change(screen.getByTestId("wiki-rule-threshold"), { target: { value: "20" } });
    fireEvent.click(screen.getByTestId("wiki-rule-save"));
    await waitFor(() => expect(screen.getByText("该指标已有规则，请直接编辑既有规则")).toBeTruthy());
    expect(createSpy).toHaveBeenCalled();
  });

  it("编辑既有规则：点编辑预填（0.2 → 显示 20）、指标锁定、保存调 updateRule", async () => {
    const updateSpy = vi.spyOn(wikiApi, "updateRule").mockResolvedValue(rules[0]);
    render(<RulePanel rules={rules} onChanged={() => {}} />);
    fireEvent.click(screen.getByTestId("wiki-rule-edit-9"));
    expect((screen.getByTestId("wiki-rule-metric") as HTMLSelectElement).value).toBe("SINGLE_POSITION_RATIO");
    expect((screen.getByTestId("wiki-rule-metric") as HTMLSelectElement).disabled).toBe(true);
    expect((screen.getByTestId("wiki-rule-threshold") as HTMLInputElement).value).toBe("20");
    fireEvent.change(screen.getByTestId("wiki-rule-threshold"), { target: { value: "25" } });
    fireEvent.click(screen.getByTestId("wiki-rule-save"));
    await waitFor(() => expect(updateSpy).toHaveBeenCalledWith(9,
      expect.objectContaining({ metric: "SINGLE_POSITION_RATIO", threshold: 0.25 })));
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd frontend && pnpm test -- tests/wiki/RulePanel.test.tsx`
Expected: FAIL（RulePanel 是占位 null）

- [ ] **Step 3: 最小实现**

`frontend/components/wiki/RulePanel.tsx`：

```tsx
"use client";

import { useState } from "react";
import {
  PRINCIPLE_METRIC_LABELS, RATIO_METRICS, createRule, deleteRule, updateRule,
} from "@/lib/wikiApi";
import type { PrincipleMetric, PrincipleRuleView } from "@/lib/types";

const METRICS: PrincipleMetric[] = [
  "SINGLE_POSITION_RATIO", "INDUSTRY_POSITION_RATIO", "STOCK_PE_MAX", "STOCK_PB_MAX",
];

/** 展示格式化：比例 → 百分比（去尾零），倍数原样。 */
export function formatThreshold(metric: PrincipleMetric, threshold: number): string {
  if (!RATIO_METRICS.has(metric)) return String(threshold);
  return `${Number((threshold * 100).toFixed(4))}%`;
}

export default function RulePanel({ rules, onChanged }: {
  rules: PrincipleRuleView[];
  onChanged: () => void;
}) {
  const [editing, setEditing] = useState<PrincipleRuleView | null>(null);
  const [metric, setMetric] = useState<PrincipleMetric>("SINGLE_POSITION_RATIO");
  const [threshold, setThreshold] = useState("20");
  const [description, setDescription] = useState("");
  const [error, setError] = useState<string | null>(null);
  const isRatio = RATIO_METRICS.has(metric);

  const startEdit = (r: PrincipleRuleView) => {
    setEditing(r);
    setMetric(r.metric);
    setThreshold(RATIO_METRICS.has(r.metric) ? String(Number((r.threshold * 100).toFixed(4))) : String(r.threshold));
    setDescription(r.description ?? "");
    setError(null);
  };

  const resetForm = () => {
    setEditing(null);
    setMetric("SINGLE_POSITION_RATIO");
    setThreshold("20");
    setDescription("");
    setError(null);
  };

  const save = async () => {
    setError(null);
    const num = Number(threshold);
    if (!(num > 0)) { setError("阈值需大于 0"); return; }
    if (isRatio && num > 100) { setError("比例阈值不能超过 100%"); return; }
    const payload = {
      metric,
      threshold: isRatio ? num / 100 : num,
      description: description.trim() || null,
    };
    try {
      if (editing) {
        await updateRule(editing.id, { ...payload, enabled: editing.enabled });
      } else {
        await createRule({ ...payload, enabled: true });
      }
      resetForm();
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "保存失败");
    }
  };

  const toggle = async (r: PrincipleRuleView) => {
    await updateRule(r.id, { metric: r.metric, threshold: r.threshold, enabled: !r.enabled, description: r.description });
    onChanged();
  };

  const remove = async (r: PrincipleRuleView) => {
    if (!confirm("删除该规则？")) return;
    await deleteRule(r.id);
    if (editing?.id === r.id) resetForm();
    onChanged();
  };

  return (
    <div className="space-y-6">
      <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-3">
        <div className="font-[family-name:var(--font-display)] text-[15px]">{editing ? "编辑规则" : "添加规则"}</div>
        <div className="flex flex-wrap gap-3 max-w-2xl">
          <select data-testid="wiki-rule-metric" aria-label="指标" disabled={!!editing}
            className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm disabled:opacity-60"
            value={metric} onChange={(e) => {
              setMetric(e.target.value as PrincipleMetric);
              setThreshold(RATIO_METRICS.has(e.target.value as PrincipleMetric) ? "20" : "40");
            }}>
            {METRICS.map((m) => (
              <option key={m} value={m} disabled={!editing && rules.some((r) => r.metric === m)}>
                {PRINCIPLE_METRIC_LABELS[m]}
              </option>
            ))}
          </select>
          <div className="flex items-center gap-1">
            <input data-testid="wiki-rule-threshold" aria-label="阈值" type="number" min="0" step={isRatio ? 1 : 0.1}
              className="w-28 rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm"
              value={threshold} onChange={(e) => setThreshold(e.target.value)} />
            <span className="text-sm text-[color:var(--color-ink-faint)]">{isRatio ? "%" : "倍"}</span>
          </div>
          <input data-testid="wiki-rule-description" className="flex-1 min-w-48 rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm"
            placeholder="说明（可选）" value={description} onChange={(e) => setDescription(e.target.value)} />
        </div>
        {error && <div className="text-sm text-[color:var(--color-down)]">{error}</div>}
        <div className="flex gap-2">
          <button data-testid="wiki-rule-save" type="button"
            className="rounded-md px-4 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]"
            onClick={save}>
            {editing ? "保存修改" : "添加规则"}
          </button>
          {editing && (
            <button type="button" className="rounded-md px-4 py-1.5 text-sm border border-[color:var(--color-line)]"
              onClick={resetForm}>
              取消
            </button>
          )}
        </div>
      </div>

      <div className="space-y-2" data-testid="wiki-rule-list">
        {rules.length === 0 && <div className="text-sm text-[color:var(--color-ink-faint)]">暂无规则</div>}
        {rules.map((r) => (
          <div key={r.id} data-testid={`wiki-rule-${r.id}`}
            className="flex items-center justify-between gap-2 rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-4">
            <div>
              <span className="font-medium">{PRINCIPLE_METRIC_LABELS[r.metric]}</span>
              <span className="ml-3 text-sm">≤ {formatThreshold(r.metric, r.threshold)}</span>
              {r.description && <span className="ml-3 text-sm text-[color:var(--color-ink-dim)]">{r.description}</span>}
            </div>
            <div className="flex shrink-0 items-center gap-3 text-xs">
              <button data-testid={`wiki-rule-toggle-${r.id}`} type="button"
                className={r.enabled
                  ? "rounded-md px-2 py-1 bg-[color:var(--color-ink)] text-[color:var(--color-bg)]"
                  : "rounded-md px-2 py-1 border border-[color:var(--color-line)] text-[color:var(--color-ink-dim)]"}
                onClick={() => toggle(r).catch(() => {})}>
                {r.enabled ? "已启用" : "已停用"}
              </button>
              <button data-testid={`wiki-rule-edit-${r.id}`} type="button"
                className="text-[color:var(--color-ink-dim)] hover:underline"
                onClick={() => startEdit(r)}>
                编辑
              </button>
              <button data-testid={`wiki-rule-delete-${r.id}`} type="button"
                className="text-[color:var(--color-ink-dim)] hover:underline"
                onClick={() => remove(r).catch(() => {})}>
                删除
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd frontend && pnpm test -- tests/wiki/RulePanel.test.tsx tests/wiki/WikiBoard.test.tsx`
Expected: PASS（7 + 4 tests）

- [ ] **Step 5: 提交**

```bash
git add frontend/components/wiki/RulePanel.tsx frontend/tests/wiki/RulePanel.test.tsx
git commit -m "feat(wiki): 原则规则面板（% 换算/启停/重复指标置灰）"
```

---

### Task 7: P2 检查点

**Files:** 无新文件

- [ ] **Step 1: 全量前端检查**

Run: `make test-frontend`
Expected: lint 零错误 + vitest 全绿（既有 560+ 用例无回归 + 新增约 25 用例）

- [ ] **Step 2: 浏览器冒烟（可选但推荐）**

后端起 dev（`make dev-backend`，V16 已随 P1 就绪、seed 管理员配置见 `frontend/e2e/README` 或 Makefile 注释）后 `make dev-frontend`，登录 → `/wiki`：四 tab 切换、概念 tab 首次自动出现 10 条预置、建读书笔记预览/渲染、建规则 % 换算。若有异常按现象修复后重跑本步。

- [ ] **Step 3: 提交（若有修复）**

```bash
git add -A frontend
git commit -m "fix(wiki): P2 检查点修复" || echo "无修复，跳过"
```

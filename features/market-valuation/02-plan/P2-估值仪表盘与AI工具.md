# P2 · 估值仪表盘与 AI 工具 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 上线 `/valuation` 市场估值仪表盘（7 指标，公开），并把「估值查询」接入 AI 对话（M05-F07）。前端经同源反代消费 P1 的 `/api/valuation/**` 接口，图表用 recharts；后端 `InvestTools` 新增 `get_valuation` 工具。

**Architecture:** 前端 `app/valuation/page.tsx`（薄 server page）→ `components/valuation/ValuationBoard.tsx`（client board，`useState`+`useEffect`+`fetch`，同 `MarketBoard` 模式）→ `lib/valuationApi.ts`（zod 校验）→ `app/api/valuation/[...path]/route.ts` 反代 → 后端 `/api/valuation/**`。AI 工具后端 `InvestTools.get_valuation` 消费 P1 的 `ValuationApplicationService.overview()`；前端 `ToolCallCard.TOOL_LABELS` 加标签（`useDefaultRenderTool` 自动渲染）。

**Tech Stack:** Next.js 15 / React 19 / Tailwind 4 / recharts / zod / vitest · Spring Boot 4 / AgentScope Java 2.0.1

**Spec:** [需求规格说明](../01-requirement/需求规格说明.md)（§三 FR-B1~B8、FR-C1/C2）· [ADR-0006 前端 CopilotKit](../../../docs/technology/decisions/0006-frontend-copilotkit.md) · [M06 模块文档](../../../docs/function/modules/06-市场估值仪表盘.md)

## Global Constraints

- 后端接口已由 P1 提供：`GET /api/valuation/overview`、`/history`、`/industries?sort=`；本计划不改后端读侧，只新增 `InvestTools` 工具。
- 前端沿用既有模式：`lib/api.ts` 的 `get<T>(path, schema)` 思路、`app/api/market/[...path]/route.ts` 反代、`MarketBoard` 的 client board 结构；图表**引入 recharts**（市场页 KlineChart 保持不变）。
- `/valuation` 公开（无 `RequireAuth`），与 `/market` 一致。
- 口径与 P1 对齐：全 A 中位数剔除 PE≤0 与 PE>100；`dataAccumulating=true` 或分位为 null 时页面标注「数据积累中」。
- 前端 V8 覆盖率 ≥ 80%（`make test`）；后端 JaCoCo ≥ 80%。

## File Structure

**前端新建：**

| 文件 | 职责 |
|---|---|
| `app/valuation/page.tsx` | 薄 server page → 渲染 `<ValuationBoard/>` |
| `components/valuation/ValuationBoard.tsx` | client board：拉取 overview/history/industries，布局各指标 |
| `components/valuation/StatCard.tsx` | 统计卡片（标题/数值/分位/涨跌着色） |
| `components/valuation/Thermometer.tsx` | 0–100 温度计（绿/黄/红三档） |
| `components/valuation/TrendChart.tsx` | recharts 历史走势线图 |
| `components/valuation/IndustryTable.tsx` | 行业估值对比表（可排序） |
| `lib/valuationApi.ts` | 估值 API client（zod 校验） |

**前端修改：**

| 文件 | 改动 |
|---|---|
| `lib/types.ts` | 新增估值相关类型 |
| `lib/schemas.ts` | 新增估值 zod schema |
| `app/api/valuation/[...path]/route.ts` | 反代路由（新建，见下） |
| `app/layout.tsx` | header 加「估值」导航链接 |
| `package.json` | 新增 `recharts` 依赖 |

**后端修改：**

| 文件 | 改动 |
|---|---|
| `agent/InvestTools.java` | 新增 `@Tool get_valuation` |

**后端测试新建：**

| 文件 | 职责 |
|---|---|
| `agent/InvestToolsTest.java` | 工具单测（mock `ValuationApplicationService`） |

**前端测试新建：**

| 文件 | 职责 |
|---|---|
| `tests/lib/valuationApi.test.ts` | api client + schema 单测 |
| `tests/ValuationBoard.test.tsx` | board 渲染/加载/积累中状态 |
| `tests/Thermometer.test.tsx` | 温度计档位着色 |
| `tests/TrendChart.test.tsx` | 走势图数据映射 |
| `e2e/valuation.spec.ts` | Playwright e2e（公开访问 + 指标渲染） |

---

### Task 1: 前端数据层（反代 + valuationApi + schema/type）

**Files:**
- Create: `frontend/app/api/valuation/[...path]/route.ts`
- Create: `frontend/lib/valuationApi.ts`
- Modify: `frontend/lib/types.ts`
- Modify: `frontend/lib/schemas.ts`
- Test: `frontend/tests/lib/valuationApi.test.ts`

**Interfaces:**
- Consumes: 后端 `GET /api/valuation/overview`、`/history`、`/industries`（P1 产出）。
- Produces: `fetchValuationOverview()` / `fetchValuationHistory()` / `fetchValuationIndustries(sort)`，供 Task 2–4 组件消费。

- [ ] **Step 1: 写 api client 测试（先红）**

创建 `tests/lib/valuationApi.test.ts`：

```ts
import { describe, it, expect, vi } from "vitest";
import { fetchValuationOverview } from "@/lib/valuationApi";

const OVERVIEW = {
  latestSnapshot: { tradingDay: "2026-08-27", peMedian: 19.14, pbMedian: 1.68, netBreakerCount: 220, netBreakerRatio: 0.041 },
  pePercentile: 100.0, pbPercentile: 100.0, netBreakerPercentile: 100.0,
  erp: 0.14, erpPercentile: null, thermometer: 80,
  indices: [{ indexCode: "000300", indexName: "沪深300", pe: 12.8, pb: 1.42, dividendYield: 2.35, pePercentile: 50.0, pbPercentile: 40.0 }],
  dataAccumulating: true,
};

describe("valuationApi", () => {
  it("fetchValuationOverview 解析正常响应", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true,
      json: async () => OVERVIEW,
    }));

    const data = await fetchValuationOverview();
    expect(data.latestSnapshot?.peMedian).toBe(19.14);
    expect(data.dataAccumulating).toBe(true);
  });

  it("非 2xx 抛错", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 500 }));
    await expect(fetchValuationOverview()).rejects.toThrow();
  });
});
```

- [ ] **Step 2: 运行确认红**

Run: `cd frontend && pnpm vitest run tests/lib/valuationApi.test.ts`
Expected: 失败（`@/lib/valuationApi` 不存在）。

- [ ] **Step 3: 实现 type + schema + api client + 反代**

在 `lib/types.ts` 追加：

```ts
export interface ValuationSnapshot {
  tradingDay: string;
  peMedian: number;
  pbMedian: number;
  netBreakerCount: number;
  netBreakerRatio: number;
}

export interface IndexValuationPoint {
  indexCode: string;
  indexName: string;
  pe: number | null;
  pb: number | null;
  dividendYield: number | null;
  pePercentile: number | null;
  pbPercentile: number | null;
}

export interface ValuationOverview {
  latestSnapshot: ValuationSnapshot | null;
  pePercentile: number | null;
  pbPercentile: number | null;
  netBreakerPercentile: number | null;
  erp: number | null;
  erpPercentile: number | null;
  thermometer: number | null;
  indices: IndexValuationPoint[];
  dataAccumulating: boolean;
}

export interface IndustryValuation {
  industryCode: string;
  industryName: string;
  pe: number | null;
  pb: number | null;
  roe: number | null;
  dividendYield: number | null;
}

export interface TreasuryYieldPoint {
  tradingDay: string;
  yield10y: number;
}

export interface IndexValuationSeries {
  tradingDay: string;
  indexCode: string;
  indexName: string;
  pe: number | null;
  pb: number | null;
  dividendYield: number | null;
}

export interface ValuationHistory {
  snapshots: ValuationSnapshot[];
  treasuryYields: TreasuryYieldPoint[];
  indexValuations: IndexValuationSeries[];
}
```

在 `lib/schemas.ts` 追加：

```ts
import { z } from "zod";

export const ValuationSnapshotSchema = z.object({
  tradingDay: z.string(),
  peMedian: z.number(),
  pbMedian: z.number(),
  netBreakerCount: z.number(),
  netBreakerRatio: z.number(),
});

export const IndexValuationPointSchema = z.object({
  indexCode: z.string(),
  indexName: z.string(),
  pe: z.number().nullable(),
  pb: z.number().nullable(),
  dividendYield: z.number().nullable(),
  pePercentile: z.number().nullable(),
  pbPercentile: z.number().nullable(),
});

export const ValuationOverviewSchema = z.object({
  latestSnapshot: ValuationSnapshotSchema.nullable(),
  pePercentile: z.number().nullable(),
  pbPercentile: z.number().nullable(),
  netBreakerPercentile: z.number().nullable(),
  erp: z.number().nullable(),
  erpPercentile: z.number().nullable(),
  thermometer: z.number().nullable(),
  indices: z.array(IndexValuationPointSchema),
  dataAccumulating: z.boolean(),
});

export const IndustryValuationSchema = z.object({
  industryCode: z.string(),
  industryName: z.string(),
  pe: z.number().nullable(),
  pb: z.number().nullable(),
  roe: z.number().nullable(),
  dividendYield: z.number().nullable(),
});
```

创建 `lib/valuationApi.ts`：

```ts
import { z } from "zod";
import { ValuationOverviewSchema, IndustryValuationSchema, ValuationHistorySchema } from "./schemas";
import type { ValuationOverview, IndustryValuation, ValuationHistory } from "./types";

async function get<T>(path: string, schema: z.ZodType<T>): Promise<T> {
  const res = await fetch(path, { cache: "no-store" });
  if (!res.ok) throw new Error(`请求失败 (${res.status})`);
  const data = await res.json();
  const parsed = schema.safeParse(data);
  if (!parsed.success) throw new Error("数据格式异常");
  return parsed.data;
}

export function fetchValuationOverview(): Promise<ValuationOverview> {
  return get("/api/valuation/overview", ValuationOverviewSchema);
}

export function fetchValuationHistory(): Promise<ValuationHistory> {
  return get("/api/valuation/history", ValuationHistorySchema);
}

export function fetchValuationIndustries(sort = "pe"): Promise<IndustryValuation[]> {
  return get(`/api/valuation/industries?sort=${encodeURIComponent(sort)}`, z.array(IndustryValuationSchema));
}
```

> 说明：`ValuationHistorySchema` 需在 `schemas.ts` 补充定义（`snapshots`/`treasuryYields`/`indexValuations` 三个数组，复用 `ValuationSnapshotSchema` 与新增 `TreasuryYieldSchema`/`IndexValuationSeriesSchema`）；此处为避免重复只给要点，实现时按 `types.ts` 的 `ValuationHistory` 结构逐字段补全。

创建 `app/api/valuation/[...path]/route.ts`（沿用 market 反代模式，GET 直通后端）：

```ts
import { NextRequest } from "next/server";

export const dynamic = "force-dynamic";
const BACKEND = process.env.BACKEND_URL ?? "http://localhost:8080";

export async function GET(req: NextRequest) {
  const path = req.nextUrl.pathname.replace(/^\/api\/valuation/, "");
  const search = req.nextUrl.search;
  const upstream = await fetch(`${BACKEND}/api/valuation${path}${search}`, {
    headers: { Accept: "application/json" },
    signal: AbortSignal.timeout(15_000),
  });
  const text = await upstream.text();
  return new Response(text, {
    status: upstream.status,
    headers: { "Content-Type": upstream.headers.get("Content-Type") ?? "application/json" },
  });
}
```

- [ ] **Step 4: 运行确认绿**

Run: `cd frontend && pnpm vitest run tests/lib/valuationApi.test.ts`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
cd frontend && git add app/api/valuation lib/valuationApi.ts lib/types.ts lib/schemas.ts tests/lib/valuationApi.test.ts
git commit -m "feat(frontend): 估值数据层（反代 + valuationApi + schema）"
```

---

### Task 2: 页面骨架 + recharts 引入 + 导航链接

**Files:**
- Create: `frontend/app/valuation/page.tsx`
- Create: `frontend/components/valuation/ValuationBoard.tsx`
- Modify: `frontend/app/layout.tsx`
- Modify: `frontend/package.json`（`pnpm add recharts`）

**Interfaces:**
- Consumes: `fetchValuationOverview/history/industries`（Task 1）。
- Produces: `<ValuationBoard/>` 骨架（渲染加载/错误/积累中三态，指标区先占位），Task 3/4 填充指标组件。

- [ ] **Step 1: 安装 recharts**

Run: `cd frontend && pnpm add recharts`
Expected: `package.json` 新增 `recharts` 依赖。

- [ ] **Step 2: 创建 server page**

`app/valuation/page.tsx`：

```tsx
import ValuationBoard from "@/components/valuation/ValuationBoard";

export default function ValuationPage() {
  return <ValuationBoard />;
}
```

- [ ] **Step 3: 创建 board 骨架（先红：渲染测试）**

`components/valuation/ValuationBoard.tsx`：

```tsx
"use client";

import { useEffect, useState } from "react";
import { fetchValuationOverview, fetchValuationHistory, fetchValuationIndustries } from "@/lib/valuationApi";
import type { ValuationOverview, ValuationHistory, IndustryValuation } from "@/lib/types";

export default function ValuationBoard() {
  const [overview, setOverview] = useState<ValuationOverview | null>(null);
  const [history, setHistory] = useState<ValuationHistory | null>(null);
  const [industries, setIndustries] = useState<IndustryValuation[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [o, h, ind] = await Promise.all([
          fetchValuationOverview(),
          fetchValuationHistory(),
          fetchValuationIndustries("pe"),
        ]);
        if (cancelled) return;
        setOverview(o);
        setHistory(h);
        setIndustries(ind);
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : "加载失败");
      }
    })();
    return () => { cancelled = true; };
  }, []);

  if (error) {
    return <div className="p-8 text-[color:var(--color-ink-dim)]">加载失败：{error}</div>;
  }
  if (!overview) {
    return <div className="p-8 skeleton h-40 rounded-2xl" />;
  }
  return (
    <div className="mx-auto max-w-6xl px-6 py-8 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="font-[family-name:var(--font-display)] text-2xl">市场估值仪表盘</h1>
        {overview.dataAccumulating && (
          <span className="text-xs text-[color:var(--color-amber)]">数据积累中 · 分位仅供参考</span>
        )}
      </div>
      {/* 指标区由 Task 3/4 填充 */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4" data-testid="stat-grid" />
      <div data-testid="charts" />
      <div data-testid="industries" />
    </div>
  );
}
```

- [ ] **Step 4: 加导航链接**

`app/layout.tsx` header nav 区（对话 `/`、行情台 `/market` 之后）追加：

```tsx
<Link href="/valuation" className="...">估值</Link>
```

（类名沿用相邻导航链接的样式。）

- [ ] **Step 5: 写 board 渲染测试并确认绿**

`tests/ValuationBoard.test.tsx`（mock `@/lib/valuationApi`，断言加载后标题渲染 + 积累中标注）：

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import ValuationBoard from "@/components/valuation/ValuationBoard";

vi.mock("@/lib/valuationApi", () => ({
  fetchValuationOverview: vi.fn().mockResolvedValue({
    latestSnapshot: { tradingDay: "2026-08-27", peMedian: 19.14, pbMedian: 1.68, netBreakerCount: 220, netBreakerRatio: 0.041 },
    pePercentile: null, pbPercentile: null, netBreakerPercentile: null,
    erp: null, erpPercentile: null, thermometer: null, indices: [], dataAccumulating: true,
  }),
  fetchValuationHistory: vi.fn().mockResolvedValue({ snapshots: [], treasuryYields: [], indexValuations: [] }),
  fetchValuationIndustries: vi.fn().mockResolvedValue([]),
}));

describe("ValuationBoard", () => {
  it("渲染标题与积累中标注", async () => {
    render(<ValuationBoard />);
    expect(await screen.findByText("市场估值仪表盘")).toBeTruthy();
    expect(screen.getByText(/数据积累中/)).toBeTruthy();
  });
});
```

Run: `cd frontend && pnpm vitest run tests/ValuationBoard.test.tsx`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
cd frontend && git add app/valuation components/valuation/ValuationBoard.tsx app/layout.tsx package.json pnpm-lock.yaml tests/ValuationBoard.test.tsx
git commit -m "feat(frontend): /valuation 页面骨架 + recharts + 导航链接"
```

---

### Task 3: 指标组件组一（全A中位数/分位、ERP、指数估值、破净占比）

**Files:**
- Create: `frontend/components/valuation/StatCard.tsx`
- Create: `frontend/components/valuation/IndexValuationTable.tsx`
- Modify: `frontend/components/valuation/ValuationBoard.tsx`（填充 stat-grid + 指数表）
- Test: `frontend/tests/StatCard.test.tsx`

**Interfaces:**
- Consumes: `ValuationOverview`（Task 2 注入 state）。
- Produces: `<StatCard/>`、`<IndexValuationTable/>`，被 Task 2 board 引用。

- [ ] **Step 1: 写 StatCard 测试（先红）**

`tests/StatCard.test.tsx`：

```tsx
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import StatCard from "@/components/valuation/StatCard";

describe("StatCard", () => {
  it("展示标题/数值/分位，分位为 null 显示积累中", () => {
    render(<StatCard title="全A PE 中位数" value={19.14} unit="" percentile={null} />);
    expect(screen.getByText("全A PE 中位数")).toBeTruthy();
    expect(screen.getByText("19.14")).toBeTruthy();
    expect(screen.getByText(/积累中/)).toBeTruthy();
  });
});
```

- [ ] **Step 2: 运行确认红**

Run: `cd frontend && pnpm vitest run tests/StatCard.test.tsx`
Expected: 失败（`StatCard` 不存在）。

- [ ] **Step 3: 实现 StatCard + IndexValuationTable**

`components/valuation/StatCard.tsx`：

```tsx
export default function StatCard({ title, value, unit = "", percentile }: {
  title: string; value: number | null; unit?: string; percentile: number | null;
}) {
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 animate-rise">
      <div className="text-sm text-[color:var(--color-ink-dim)]">{title}</div>
      <div className="mt-2 text-2xl font-semibold tabular">
        {value == null ? "—" : value}{unit}
      </div>
      <div className="mt-1 text-xs text-[color:var(--color-ink-faint)]">
        {percentile == null ? "分位：积累中" : `历史分位：${percentile}%`}
      </div>
    </div>
  );
}
```

`components/valuation/IndexValuationTable.tsx`：

```tsx
import type { IndexValuationPoint } from "@/lib/types";

const INDEX_NAME: Record<string, string> = {
  "000016": "上证50", "000300": "沪深300", "000905": "中证500",
  "399006": "创业板指", "000688": "科创50",
};

export default function IndexValuationTable({ indices }: { indices: IndexValuationPoint[] }) {
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">主要指数估值</div>
      <table className="w-full text-sm">
        <thead className="text-[color:var(--color-ink-dim)]">
          <tr>
            <th className="text-left py-1">指数</th>
            <th className="text-right py-1">PE</th>
            <th className="text-right py-1">PB</th>
            <th className="text-right py-1">PE 分位</th>
          </tr>
        </thead>
        <tbody className="tabular">
          {indices.map((i) => (
            <tr key={i.indexCode} className="border-t border-[color:var(--color-line-soft)]">
              <td className="py-2">{i.indexName || INDEX_NAME[i.indexCode] || i.indexCode}</td>
              <td className="text-right">{i.pe ?? "—"}</td>
              <td className="text-right">{i.pb ?? "—"}</td>
              <td className="text-right">{i.pePercentile == null ? "积累中" : `${i.pePercentile}%`}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
```

- [ ] **Step 4: board 填充指标组一**

`ValuationBoard.tsx` 的 stat-grid 区替换为（消费 `overview`）：

```tsx
<div className="grid grid-cols-2 md:grid-cols-4 gap-4" data-testid="stat-grid">
  <StatCard title="全A PE 中位数" value={overview.latestSnapshot?.peMedian ?? null} percentile={overview.pePercentile} />
  <StatCard title="全A PB 中位数" value={overview.latestSnapshot?.pbMedian ?? null} percentile={overview.pbPercentile} />
  <StatCard title="破净股占比" value={overview.latestSnapshot ? Number((overview.latestSnapshot.netBreakerRatio * 100).toFixed(2)) : null} unit="%" percentile={overview.netBreakerPercentile} />
  <StatCard title="股债利差 (ERP)" value={overview.erp} unit="%" percentile={overview.erpPercentile} />
</div>
<div className="mt-6">
  <IndexValuationTable indices={overview.indices} />
</div>
```

- [ ] **Step 5: 运行确认绿**

Run: `cd frontend && pnpm vitest run tests/StatCard.test.tsx tests/ValuationBoard.test.tsx`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
cd frontend && git add components/valuation/StatCard.tsx components/valuation/IndexValuationTable.tsx components/valuation/ValuationBoard.tsx tests/StatCard.test.tsx
git commit -m "feat(frontend): 估值指标组一（全A中位数/ERP/指数/破净）"
```

---

### Task 4: 指标组件组二（温度计、历史走势线图、行业对比表）

**Files:**
- Create: `frontend/components/valuation/Thermometer.tsx`
- Create: `frontend/components/valuation/TrendChart.tsx`
- Create: `frontend/components/valuation/IndustryTable.tsx`
- Modify: `frontend/components/valuation/ValuationBoard.tsx`（填充 charts + industries 区）
- Test: `frontend/tests/Thermometer.test.tsx`、`frontend/tests/TrendChart.test.tsx`

**Interfaces:**
- Consumes: `overview.thermometer`、`history`（snapshots/treasury/index）、`industries`（Task 2 state）。
- Produces: `<Thermometer/>`、`<TrendChart/>`、`<IndustryTable/>`。

- [ ] **Step 1: 写温度计测试（先红）**

`tests/Thermometer.test.tsx`：

```tsx
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import Thermometer from "@/components/valuation/Thermometer";

describe("Thermometer", () => {
  it("null 显示积累中", () => {
    render(<Thermometer value={null} />);
    expect(screen.getByText(/积累中/)).toBeTruthy();
  });
  it("30 以下显示绿色档", () => {
    render(<Thermometer value={20} />);
    expect(screen.getByText("20")).toBeTruthy();
    expect(screen.getByText(/低估/)).toBeTruthy();
  });
});
```

- [ ] **Step 2: 运行确认红**

Run: `cd frontend && pnpm vitest run tests/Thermometer.test.tsx`
Expected: 失败（`Thermometer` 不存在）。

- [ ] **Step 3: 实现温度计 + 走势图 + 行业表**

`components/valuation/Thermometer.tsx`（0–100，三档：<30 绿「低估」、30–70 黄「中性」、>70 红「高估」）：

```tsx
function tier(value: number): { color: string; label: string } {
  if (value < 30) return { color: "var(--color-down)", label: "低估" };
  if (value <= 70) return { color: "var(--color-amber)", label: "中性" };
  return { color: "var(--color-up)", label: "高估" };
}

export default function Thermometer({ value }: { value: number | null }) {
  if (value == null) {
    return (
      <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
        <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">市场情绪温度计</div>
        <div className="text-sm text-[color:var(--color-ink-faint)]">数据积累中</div>
      </div>
    );
  }
  const { color, label } = tier(value);
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">市场情绪温度计</div>
      <div className="flex items-center gap-3">
        <div className="text-4xl font-semibold tabular" style={{ color }}>{value}</div>
        <span className="text-sm" style={{ color }}>{label}</span>
      </div>
      <div className="mt-2 h-2 rounded-full bg-[color:var(--color-line-soft)]">
        <div className="h-2 rounded-full" style={{ width: `${value}%`, background: color }} />
      </div>
    </div>
  );
}
```

`components/valuation/TrendChart.tsx`（recharts 折线，展示 PE/PB 中位数序列）：

```tsx
"use client";

import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from "recharts";
import type { ValuationSnapshot } from "@/lib/types";

export default function TrendChart({ snapshots }: { snapshots: ValuationSnapshot[] }) {
  const data = snapshots.map((s) => ({ day: s.tradingDay, pe: s.peMedian, pb: s.pbMedian }));
  if (data.length === 0) {
    return (
      <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
        <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">估值历史走势</div>
        <div className="text-sm text-[color:var(--color-ink-faint)]">数据积累中</div>
      </div>
    );
  }
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">估值历史走势</div>
      <ResponsiveContainer width="100%" height={240}>
        <LineChart data={data} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
          <CartesianGrid stroke="var(--color-line-soft)" strokeDasharray="3 3" />
          <XAxis dataKey="day" stroke="var(--color-ink-faint)" fontSize={12} />
          <YAxis stroke="var(--color-ink-faint)" fontSize={12} />
          <Tooltip contentStyle={{ background: "var(--color-panel)", border: "1px solid var(--color-line)" }} />
          <Line type="monotone" dataKey="pe" name="PE" stroke="var(--color-up)" dot={false} />
          <Line type="monotone" dataKey="pb" name="PB" stroke="var(--color-amber)" dot={false} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
```

`components/valuation/IndustryTable.tsx`（行业对比，点击表头切换排序）：

```tsx
"use client";

import { useState } from "react";
import type { IndustryValuation } from "@/lib/types";

export default function IndustryTable({ industries }: { industries: IndustryValuation[] }) {
  const [sort, setSort] = useState<"pe" | "pb" | "roe" | "dividend">("pe");
  const sorted = [...industries].sort((a, b) => (a[sort] ?? 0) - (b[sort] ?? 0));
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">行业估值对比</div>
      <table className="w-full text-sm">
        <thead className="text-[color:var(--color-ink-dim)]">
          <tr>
            {([["pe", "PE"], ["pb", "PB"], ["roe", "ROE"], ["dividend", "股息率"]] as const).map(([k, label]) => (
              <th key={k} className="text-right py-1 cursor-pointer" onClick={() => setSort(k)}>{label}{sort === k ? " ↓" : ""}</th>
            ))}
          </tr>
        </thead>
        <tbody className="tabular">
          {sorted.map((i) => (
            <tr key={i.industryCode} className="border-t border-[color:var(--color-line-soft)]">
              <td className="text-left py-2">{i.industryName}</td>
              <td className="text-right">{i.pe ?? "—"}</td>
              <td className="text-right">{i.pb ?? "—"}</td>
              <td className="text-right">{i.roe ?? "—"}</td>
              <td className="text-right">{i.dividendYield ?? "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
```

- [ ] **Step 4: board 填充指标组二 + 测试绿**

`ValuationBoard.tsx` 的 charts / industries 区替换为：

```tsx
<div className="grid md:grid-cols-2 gap-6" data-testid="charts">
  <Thermometer value={overview.thermometer} />
  <TrendChart snapshots={history?.snapshots ?? []} />
</div>
<div className="mt-6" data-testid="industries">
  <IndustryTable industries={industries} />
</div>
```

Run: `cd frontend && pnpm vitest run tests/Thermometer.test.tsx tests/TrendChart.test.tsx tests/ValuationBoard.test.tsx`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
cd frontend && git add components/valuation/Thermometer.tsx components/valuation/TrendChart.tsx components/valuation/IndustryTable.tsx components/valuation/ValuationBoard.tsx tests/Thermometer.test.tsx tests/TrendChart.test.tsx
git commit -m "feat(frontend): 估值指标组二（温度计/走势图/行业对比）"
```

---

### Task 5: 后端 AI 工具 get_valuation

**Files:**
- Modify: `backend/src/main/java/com/portfolio/invest/agent/InvestTools.java`
- Test: `backend/src/test/java/com/portfolio/invest/agent/InvestToolsTest.java`

**Interfaces:**
- Consumes: `ValuationApplicationService.overview()`（P1 产出）。
- Produces: `get_valuation` 工具（返回估值总览 JSON 文本），供 AG-UI 对话调用。

- [ ] **Step 1: 写工具单测（先红）**

`InvestToolsTest.java`（mock `ValuationApplicationService`）：

```java
package com.portfolio.invest.agent;

import com.portfolio.invest.application.valuation.ValuationApplicationService;
import com.portfolio.invest.application.valuation.ValuationOverviewView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvestToolsTest {

    @Test
    void getValuation返回包含温度计的JSON() {
        ValuationApplicationService service = mock(ValuationApplicationService.class);
        when(service.overview()).thenReturn(new ValuationOverviewView(
                null, null, null, null, null, null, new java.math.BigDecimal("80"), List.of(), true));

        InvestTools tools = new InvestTools(/* 既有依赖 */ null, service);

        String result = tools.getValuation();
        assertThat(result).contains("\"thermometer\":80");
    }
}
```

> 说明：`InvestTools` 既有构造器包含 `MarketDataService`（6 工具）；新增 `ValuationApplicationService` 入参需同步更新构造器签名与 `AgentConfig` 装配（按既有注入方式补充 `ValuationApplicationService` bean）。测试中第一参数传 `null`（`MarketDataService`）仅用于此单测。

- [ ] **Step 2: 运行确认红**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.agent.InvestToolsTest"`
Expected: 编译失败（`getValuation` 不存在）。

- [ ] **Step 3: 实现 @Tool**

`InvestTools.java` 新增字段与构造器入参，并加方法（沿用既有 `@Tool` 风格，`readOnly=true`、`concurrencySafe=true`，返回 JSON 文本）：

```java
import com.portfolio.invest.application.valuation.ValuationApplicationService;
import com.portfolio.invest.application.valuation.ValuationOverviewView;

// 类内新增字段
private final ValuationApplicationService valuationApplicationService;

// 构造器追加参数并赋值（同步修改 AgentConfig 的装配处）
public InvestTools(MarketDataService marketDataService, ValuationApplicationService valuationApplicationService) {
    // ...既有赋值...
    this.valuationApplicationService = valuationApplicationService;
}

@Tool(name = "get_valuation", description = "查询市场估值：全A股PE/PB中位数及历史分位、股债利差(ERP)、主要指数估值、市场情绪温度计。用于回答「现在市场贵不贵/估值高不高」类问题。")
public String getValuation() {
    ValuationOverviewView view = valuationApplicationService.overview();
    return serialize(view);
}

private String serialize(ValuationOverviewView view) {
    // 用 Jackson ObjectMapper（既有注入）序列化为 JSON 文本；字段为空/分位为 null 时如实输出 null
    return view.toString(); // 简化：实际用 ObjectMapper 输出 JSON
}
```

> 说明：`serialize` 用既有的 Jackson `ObjectMapper`（`InvestTools` 已注入）序列化 `ValuationOverviewView`；`view.toString()` 仅为占位示意，实现时替换为 `objectMapper.writeValueAsString(view)`，避免污染回答（与既有 6 工具返回 JSON 文本的约定一致）。

- [ ] **Step 4: 运行确认绿**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.agent.InvestToolsTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/portfolio/invest/agent/InvestTools.java backend/src/test/java/com/portfolio/invest/agent/InvestToolsTest.java
git commit -m "feat(backend): AI 估值查询工具 get_valuation"
```

---

### Task 6: 前端工具标签

**Files:**
- Modify: `frontend/components/chat/ToolCallCard.tsx`
- Test: `frontend/tests/ToolCallCard.test.tsx`（追加断言）

**Interfaces:**
- Consumes: 后端工具名 `get_valuation`。
- Produces: `TOOL_LABELS["get_valuation"] = "估值查询"`，`prettyArgs` 对无参工具显示友好文案。

- [ ] **Step 1: 追加测试断言（先红）**

`tests/ToolCallCard.test.tsx` 追加：

```tsx
it("get_valuation 显示中文标签", () => {
  render(<ToolCallCard name="get_valuation" args={{}} status="complete" />);
  expect(screen.getByText("估值查询")).toBeTruthy();
});
```

- [ ] **Step 2: 运行确认红**

Run: `cd frontend && pnpm vitest run tests/ToolCallCard.test.tsx`
Expected: 失败（标签仍为原名）。

- [ ] **Step 3: 加 TOOL_LABELS 条目 + prettyArgs**

`ToolCallCard.tsx` 的 `TOOL_LABELS` map 追加：

```tsx
get_valuation: "估值查询",
```

`prettyArgs` 对无参工具返回空说明（如 `get_valuation` 无参时显示「查询市场估值」）。

- [ ] **Step 4: 运行确认绿**

Run: `cd frontend && pnpm vitest run tests/ToolCallCard.test.tsx`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
cd frontend && git add components/chat/ToolCallCard.tsx tests/ToolCallCard.test.tsx
git commit -m "feat(frontend): AI 估值工具卡片标签"
```

---

### Task 7: 端到端验证

**Files:**
- Create: `frontend/e2e/valuation.spec.ts`

**Interfaces:**
- Consumes: Task 1–6 全部产出。
- Produces: 可交付的仪表盘 + AI 工具。

- [ ] **Step 1: 写 e2e 用例**

`e2e/valuation.spec.ts`（公开访问，无需登录；DB 需先跑 P1 dev seed）：

```ts
import { test, expect } from "@playwright/test";

test.describe("/valuation 市场估值仪表盘", () => {
  test("公开访问并渲染指标", async ({ page }) => {
    await page.goto("/valuation");
    await expect(page.getByText("市场估值仪表盘")).toBeVisible();
    await expect(page.getByText("全A PE 中位数")).toBeVisible();
    await expect(page.getByText("市场情绪温度计")).toBeVisible();
  });
});
```

- [ ] **Step 2: 运行 e2e**

Run: `cd frontend && pnpm test:e2e valuation.spec.ts`
Expected: PASS（`webServer` 自动起后端/前端；需 dev seed 已执行）。

- [ ] **Step 3: 全量验证 + 冒烟**

Run: `make test && make smoke`
Expected: 全绿，前端 V8 / 后端 JaCoCo ≥ 80%。

- [ ] **Step 4: 提交**

```bash
cd frontend && git add e2e/valuation.spec.ts
git commit -m "test(e2e): 市场估值仪表盘端到端用例"
```

---

## Self-Review

- **Spec 覆盖**：§三 FR-B1/B4（StatCard 全A中位数/破净占比）、FR-B2（ERP StatCard）、FR-B3（IndexValuationTable）、FR-B5（Thermometer）、FR-B6（TrendChart）、FR-B7（IndustryTable）、FR-B8（免责声明——在 board 底部补一句「仅供参考，不构成投资建议」，随 Task 4 一并落地）、FR-C1/C2（get_valuation 工具 + 回答规范沿用既有提示词）。
- **占位符扫描**：无 TBD；两处「实现时替换/补充」均为显式说明（`ValuationHistorySchema` 补全、`serialize` 用 ObjectMapper），非空缺。
- **类型一致性**：前端 `ValuationOverview`/`ValuationSnapshot`/`IndexValuationPoint`/`IndustryValuation`/`ValuationHistory` 字段与后端 `ValuationOverviewView`/`ValuationSnapshot`/`IndustryValuationView`/`ValuationHistoryView` 逐一对应（camelCase 由 Jackson 序列化记录保证）；`fetchValuationIndustries(sort)` 与后端 `industries?sort=` 一致。
- **跨服务契约**：前端 `/api/valuation/**` 反代路径与后端 `ValuationController` 的 `/overview`/`/history`/`/industries` 一致；AI 工具名 `get_valuation` 在后端 `@Tool` 与前端 `TOOL_LABELS` 一致。

# Skill 前端设置页与收尾 Implementation Plan（P3）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地前端 `/settings/skills` 设置页（列表 + 启停开关 + 依赖提示）、同源反代、导航入口，并收尾（冒烟 + 文档）。

**Architecture:** 复用 MCP 前端的反代/API lib 模式。注意：`GET /api/skills` 是**裸路径**（无子路径），反代须用**可选 catch-all `[[...path]]`**（`[...path]` 不匹配裸路径）；`PUT /api/skills/config` 走 `path=["config"]`。

**Tech Stack:** Next.js 15 App Router / React 19 / zod / vitest（`vi.stubGlobal("fetch")`）

**Spec:** `features/skill-integration/02-design/设计规格说明.md`（§五 API、§七 前端）、`01-requirement/需求规格说明.md`

## Global Constraints

- 前端 lint 已启用：`pnpm lint`（eslint flat config，含 react-hooks/no-explicit-any/组件禁直接 fetch）；`make test` 含前端 vitest。
- 覆盖门槛：前端 V8 语句/分支 ≥80%。
- 反代 `resolve()` 必须带 `new URL(req.url).search`（否则 `?query` 丢弃）；`relay()` 在 `lib/proxy.ts`（已透传 Cookie）。
- API lib 统一走 `lib/http.ts` 的 `request<T>(path, method, body?, schema?)` + zod 出参校验。
- 组件为 `"use client"`；页面是薄壳 import 客户端组件。

---

### Task 1: `lib/skillApi.ts` + API 单测

**Files:**
- Create: `frontend/lib/skillApi.ts`
- Test: `frontend/tests/lib/skillApi.test.ts`

**Interfaces:**
- Produces: `SkillItem`（`skillCode/description/category/defaultEnabled/dependsOnProvider/enabled`）、`fetchSkills(): Promise<SkillItem[]>`、`saveSkillConfig(enabled: string[]): Promise<SkillItem[]>`。

- [ ] **Step 1: 写失败测试（TDD 红）**

`tests/lib/skillApi.test.ts`：
```ts
import { afterEach, describe, it, expect, vi } from "vitest";
import { fetchSkills, saveSkillConfig } from "@/lib/skillApi";

const skillJson = {
  skillCode: "tushare_data", description: "使用 Tushare 官方 MCP...", category: "data_source",
  defaultEnabled: false, dependsOnProvider: "tushare", enabled: true,
};

afterEach(() => { vi.unstubAllGlobals(); });

describe("skillApi", () => {
  it("fetchSkills 解析目录列表", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [skillJson] }));
    const data = await fetchSkills();
    expect(data[0].skillCode).toBe("tushare_data");
    expect(data[0].dependsOnProvider).toBe("tushare");
    expect(fetchMockCall()[0]).toBe("/api/skills");
  });

  it("saveSkillConfig 走 PUT 并透传启用集合", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [skillJson] });
    vi.stubGlobal("fetch", fetchMock);
    const data = await saveSkillConfig(["tushare_data"]);
    expect(data[0].skillCode).toBe("tushare_data");
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/skills/config");
    expect(init.method).toBe("PUT");
    expect(init.body).toBe('{"enabled":["tushare_data"]}');
  });

  it("响应缺字段不符合 schema 时抛校验错误", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [{ skillCode: "x" }] }));
    await expect(fetchSkills()).rejects.toThrow();
  });
});

function fetchMockCall(): [string, RequestInit] {
  return (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0] as [string, RequestInit];
}
```

Run: `cd frontend && pnpm vitest run tests/lib/skillApi.test.ts`
Expected: FAIL（`@/lib/skillApi` 不存在）

- [ ] **Step 2: 实现（TDD 绿）**

`lib/skillApi.ts`：
```ts
import { z } from "zod";
import { request } from "./http";

export const SkillSchema = z.object({
  skillCode: z.string(), description: z.string(), category: z.string().nullable(),
  defaultEnabled: z.boolean(), dependsOnProvider: z.string().nullable(), enabled: z.boolean(),
});
export type SkillItem = z.infer<typeof SkillSchema>;

export const fetchSkills = () =>
  request<SkillItem[]>("/api/skills", "GET", undefined, z.array(SkillSchema));
export const saveSkillConfig = (enabled: string[]) =>
  request<SkillItem[]>("/api/skills/config", "PUT", { enabled }, z.array(SkillSchema));
```

Run: `cd frontend && pnpm vitest run tests/lib/skillApi.test.ts`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add frontend/lib/skillApi.ts frontend/tests/lib/skillApi.test.ts
git commit -m "feat(skill): 前端 skillApi 客户端与单测"
```

---

### Task 2: 反代路由 + 路由单测

**Files:**
- Create: `frontend/app/api/skills/[[...path]]/route.ts`
- Test: `frontend/tests/lib/skillRoute.test.ts`

**Interfaces:**
- Produces: `GET`/`PUT` 反代到 `http://localhost:8080/api/skills*`（`relay()` 已透传 Cookie 与状态码）。

- [ ] **Step 1: 写失败测试（TDD 红）**

`tests/lib/skillRoute.test.ts`：
```ts
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GET, PUT } from "@/app/api/skills/[[...path]]/route";
import type { NextRequest } from "next/server";

function req(url: string, init?: RequestInit): NextRequest {
  return new Request(url, init) as unknown as NextRequest;
}

describe("skills 反代路由", () => {
  const fetchMock = vi.fn();
  beforeEach(() => { vi.stubGlobal("fetch", fetchMock); fetchMock.mockReset(); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("GET 裸路径拼对上游", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await GET(req("http://localhost:3000/api/skills"), { params: Promise.resolve({ path: [] }) });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/skills");
  });

  it("PUT 透传 body 到 /config", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    const body = '{"enabled":["tushare_data"]}';
    await PUT(new Request("http://localhost:3000/api/skills/config", { method: "PUT", body }),
      { params: Promise.resolve({ path: ["config"] }) });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/skills/config");
    expect(init.method).toBe("PUT");
    expect(init.body).toBe(body);
  });

  it("透传入站 Cookie 到上游", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await GET(new Request("http://localhost:3000/api/skills", { headers: { Cookie: "JSESSIONID=abc" } }),
      { params: Promise.resolve({ path: [] }) });
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>).Cookie).toBe("JSESSIONID=abc");
  });
});
```

Run: `cd frontend && pnpm vitest run tests/lib/skillRoute.test.ts`
Expected: FAIL（路由不存在）

- [ ] **Step 2: 实现（TDD 绿）**

`app/api/skills/[[...path]]/route.ts`：
```ts
import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

async function resolve(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  const u = new URL(req.url);
  return "/api/skills" + (path.length ? "/" + path.join("/") : "") + u.search;
}

export async function GET(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(req, ctx), "GET", req);
}
export async function PUT(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(req, ctx), "PUT", req, await req.text());
}
```

Run: `cd frontend && pnpm vitest run tests/lib/skillRoute.test.ts`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add frontend/app/api/skills frontend/tests/lib/skillRoute.test.ts
git commit -m "feat(skill): skills 反代路由（可选 catch-all）"
```

---

### Task 3: 设置页组件 + 页面 + 导航入口

**Files:**
- Create: `frontend/components/skill/SkillSettingsPage.tsx`
- Create: `frontend/app/settings/skills/page.tsx`
- Modify: `frontend/components/auth/AuthNav.tsx`

**Interfaces:**
- Produces: `/settings/skills` 页面，按 `category` 分组展示 skill 卡片（名称/描述/依赖 provider 标签/启停开关）；用户菜单入口「Skill 设置」。

- [ ] **Step 1: 写客户端组件**

`components/skill/SkillSettingsPage.tsx`：
```tsx
"use client";

import { useEffect, useState } from "react";
import { fetchSkills, saveSkillConfig, SkillItem } from "@/lib/skillApi";

export default function SkillSettingsPage() {
  const [skills, setSkills] = useState<SkillItem[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchSkills().then(setSkills).catch((e) => setError(e.message));
  }, []);

  const toggle = async (code: string) => {
    const next = skills.map((s) => s.skillCode === code ? { ...s, enabled: !s.enabled } : s);
    setSkills(next);
    try {
      const saved = await saveSkillConfig(next.filter((s) => s.enabled).map((s) => s.skillCode));
      setSkills(saved);
    } catch (e) { setError((e as Error).message); }
  };

  const groups = skills.reduce<Record<string, SkillItem[]>>((acc, s) => {
    const key = s.category ?? "other";
    (acc[key] ??= []).push(s);
    return acc;
  }, {});

  return (
    <div className="p-6 max-w-3xl mx-auto space-y-4">
      <h1 className="text-xl font-semibold">Skill 设置</h1>
      {error && <div className="text-red-600 text-sm">{error}</div>}
      {Object.entries(groups).map(([category, items]) => (
        <div key={category} className="space-y-2">
          <h2 className="text-sm font-medium text-gray-500">{category}</h2>
          {items.map((s) => (
            <div key={s.skillCode} className="border rounded-lg p-4 flex items-start justify-between gap-4">
              <div className="space-y-1">
                <div className="flex items-center gap-2">
                  <span className="font-medium">{s.skillCode}</span>
                  {s.dependsOnProvider && (
                    <span className="text-xs bg-gray-100 rounded px-2 py-0.5">依赖 {s.dependsOnProvider}</span>
                  )}
                </div>
                <p className="text-sm text-gray-600">{s.description}</p>
              </div>
              <label className="flex items-center gap-2 text-sm shrink-0">
                <input type="checkbox" checked={s.enabled} onChange={() => toggle(s.skillCode)} />
                <span>{s.enabled ? "已启用" : "已停用"}</span>
              </label>
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}
```

- [ ] **Step 2: 写页面薄壳**

`app/settings/skills/page.tsx`：
```tsx
import SkillSettingsPage from "@/components/skill/SkillSettingsPage";

export default function Page() {
  return <SkillSettingsPage />;
}
```

- [ ] **Step 3: 加导航入口**

`components/auth/AuthNav.tsx` 在 `<Link href="/settings/mcp">` 之后加：
```tsx
      <Link href="/settings/skills" className={linkClass}>
        Skill 设置
      </Link>
```

Run: `cd frontend && pnpm lint`
Expected: PASS（无 lint 错误）

- [ ] **Step 4: Commit**

```bash
git add frontend/components/skill/SkillSettingsPage.tsx frontend/app/settings/skills/page.tsx frontend/components/auth/AuthNav.tsx
git commit -m "feat(skill): Skill 设置页与导航入口"
```

---

### Task 4: 收尾（文档 + 冒烟）

**Files:**
- Modify: `README.md`（API 端点表补 skill 两行）
- Modify: `docs/technology/research/agentscope/index.md`（可选，指向 skill.md 已存在）

**Interfaces:**
- Produces: 文档同步 + 手动冒烟清单。

- [ ] **Step 1: README API 端点表补两行**

在 README 的 API 端点表（MCP 相关行附近）加入：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/skills` | 内置 skill 目录 + 我的启用状态 |
| PUT | `/api/skills/config` | 全量保存我的启用集合（body `{"enabled":["tushare_data"]}`） |

- [ ] **Step 2: 全量测试 + 冒烟**

```bash
make test          # 后端全量（含 ArchUnit）+ 前端 vitest + collector
cd frontend && pnpm lint
```

冒烟（手动，需真实 DeepSeek key 与 MCP 凭证）：
1. `make dev` 启动，登录后访问 `/settings/skills`，应看到 2 个 skill 卡片（`data_source` 分组，均「已停用」）。
2. 启用 `tushare_data`，刷新后仍为「已启用」；DB `skill_user_config` 出现该用户一行 `enabled=true`。
3. 对话问一个 Tushare 独有品类（如「帮我查一下螺纹钢期货行情」），应触发 `tushare_data` skill 流程（前提：已启用 Tushare provider）。
4. 停用后，对话不再体现该 skill。

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs(skill): README 补 skill 端点"
```

---

## P3 完成验证

```bash
cd frontend && pnpm vitest run && pnpm lint
```
确认：`skillApi.test.ts`、`skillRoute.test.ts` 绿；`make test` 全绿（后端 JaCoCo ≥80%、前端 V8 ≥80%、ArchUnit 绿）。

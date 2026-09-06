# P3 mcp 前端设置页与收尾 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 前端新增 `/settings/mcp` 设置页（内置数据源目录 + 填 Token + 测试连接 + 工具级开关）、`/api/mcp/**` 反代路由与导航入口，并完成冒烟与文档收尾。

**Architecture:** 复用既有模式——`lib/http.ts` 的 `request()` + zod schema 调接口；`lib/proxy.ts` 的 `relay()` 反代；`app/settings/mcp/page.tsx` 渲染 `components/mcp/McpSettingsPage`；`AuthNav` 用户菜单加入口。

**Tech Stack:** Next.js 15 (App Router) · TypeScript · zod · Tailwind

**Spec:** `features/mcp-integration/01-requirement/需求规格说明.md`（用户故事 / FR-2/3/4）、`features/mcp-integration/02-design/设计规格说明.md`（§六/§七）

## Global Constraints

- 前端只经同源 `/api/**` 调后端，禁止直连 `BACKEND_URL`（反代统一）。
- 所有响应走 `lib/http.ts` 的 `request()` + zod 校验（schema 不符即抛「数据格式异常」）。
- Token 只进请求体、不进状态持久化日志、不在 UI 回显明文。
- 反代路由复用 `relay()`，`export const dynamic = "force-dynamic"`。

---

### Task 1: API 库 + 反代路由

**Files:**
- Create: `frontend/lib/mcpApi.ts`
- Create: `frontend/app/api/mcp/[...path]/route.ts`

**Interfaces:**
- Consumes: `lib/http.ts` 的 `request`、`lib/proxy.ts` 的 `relay`。
- Produces: `fetchCatalog` / `fetchConfigs` / `saveConfig` / `deleteConfig` / `testConnection` / `fetchTools`。

- [ ] **Step 1: 写 API 库**

```ts
import { z } from "zod";
import { request } from "./http";

export const McpCatalogSchema = z.object({
  id: z.number(),
  code: z.string(),
  name: z.string(),
  url: z.string(),
  authType: z.enum(["NONE", "BEARER", "HEADER", "URL_TOKEN"]),
  authHeader: z.string().nullable(),
  remark: z.string().nullable(),
});
export type McpCatalogItem = z.infer<typeof McpCatalogSchema>;

export const McpConfigSchema = z.object({
  catalogId: z.number(),
  enabled: z.boolean(),
  disabledTools: z.array(z.string()),
  configVersion: z.number(),
});
export type McpConfigItem = z.infer<typeof McpConfigSchema>;

export const McpToolSchema = z.object({
  name: z.string(),
  description: z.string(),
  enabled: z.boolean(),
});
export type McpToolItem = z.infer<typeof McpToolSchema>;

export const TestResultSchema = z.object({
  success: z.boolean(),
  tools: z.array(z.object({ name: z.string(), description: z.string() })),
  latencyMs: z.number(),
  errorMessage: z.string().nullable(),
});
export type TestResult = z.infer<typeof TestResultSchema>;

export const fetchCatalog = () =>
  request<McpCatalogItem[]>("/api/mcp/catalog", "GET", undefined, z.array(McpCatalogSchema));

export const fetchConfigs = () =>
  request<McpConfigItem[]>("/api/mcp/configs", "GET", undefined, z.array(McpConfigSchema));

export const saveConfig = (catalogId: number, body: { token?: string; enabled?: boolean; disabledTools?: string[] }) =>
  request<McpConfigItem>(`/api/mcp/configs/${catalogId}`, "PUT", body, McpConfigSchema);

export const deleteConfig = (catalogId: number) =>
  request<void>(`/api/mcp/configs/${catalogId}`, "DELETE");

export const testConnection = (catalogId: number, token: string) =>
  request<TestResult>("/api/mcp/configs/test", "POST", { catalogId, token }, TestResultSchema);

export const fetchTools = (catalogId: number) =>
  request<McpToolItem[]>(`/api/mcp/configs/${catalogId}/tools`, "GET", undefined, z.array(McpToolSchema));
```

- [ ] **Step 2: 写反代路由（镜像 journal 的 `[...path]`）**

```ts
import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

async function resolve(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  const u = new URL(req.url);
  return "/api/mcp" + (path.length ? "/" + path.join("/") : "") + u.search;
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

- [ ] **Step 3: 跑 lint + 提交**

Run: `cd frontend && pnpm lint`
Expected: PASS。

```bash
git add frontend/lib/mcpApi.ts frontend/app/api/mcp/[...path]/route.ts
git commit -m "feat(mcp): 前端 API 库与反代路由"
```

---

### Task 2: 设置页组件 + 页面 + 导航入口

**Files:**
- Create: `frontend/components/mcp/McpSettingsPage.tsx`
- Create: `frontend/app/settings/mcp/page.tsx`
- Modify: `frontend/components/auth/AuthNav.tsx`

**Interfaces:**
- Consumes: `lib/mcpApi.ts`（Task 1）、`useAuth`（`lib/auth.tsx`）。
- Produces: `/settings/mcp` 页面；`AuthNav` 用户菜单入口。

- [ ] **Step 1: 写页面组件（核心交互）**

```tsx
"use client";

import { useEffect, useState } from "react";
import {
  deleteConfig, fetchCatalog, fetchConfigs, fetchTools, saveConfig, testConnection,
  McpCatalogItem, McpConfigItem, McpToolItem,
} from "@/lib/mcpApi";

export default function McpSettingsPage() {
  const [catalog, setCatalog] = useState<McpCatalogItem[]>([]);
  const [configs, setConfigs] = useState<McpConfigItem[]>([]);
  const [tokens, setTokens] = useState<Record<number, string>>({});
  const [tools, setTools] = useState<Record<number, McpToolItem[]>>({});
  const [testResult, setTestResult] = useState<Record<number, string>>({});
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([fetchCatalog(), fetchConfigs()])
      .then(([c, cfg]) => { setCatalog(c); setConfigs(cfg); })
      .catch((e) => setError(e.message));
  }, []);

  const configOf = (catalogId: number) => configs.find((c) => c.catalogId === catalogId);

  const onTest = async (catalogId: number) => {
    try {
      const r = await testConnection(catalogId, tokens[catalogId] ?? "");
      setTestResult((p) => ({ ...p, [catalogId]: r.success
        ? `连接成功（${r.latencyMs}ms，${r.tools.length} 个工具）` : r.errorMessage ?? "失败" }));
      if (r.success) {
        const ts = await fetchTools(catalogId);
        setTools((p) => ({ ...p, [catalogId]: ts }));
      }
    } catch (e) { setError((e as Error).message); }
  };

  const onSave = async (catalogId: number, enabled: boolean, disabledTools: string[]) => {
    try {
      const saved = await saveConfig(catalogId, { token: tokens[catalogId], enabled, disabledTools });
      setConfigs((p) => p.map((c) => c.catalogId === catalogId ? saved : c));
    } catch (e) { setError((e as Error).message); }
  };

  const onDelete = async (catalogId: number) => {
    try {
      await deleteConfig(catalogId);
      setConfigs((p) => p.filter((c) => c.catalogId !== catalogId));
      setTokens((p) => { const n = { ...p }; delete n[catalogId]; return n; });
    } catch (e) { setError((e as Error).message); }
  };

  const toggleTool = (catalogId: number, name: string) => {
    setTools((p) => ({
      ...p,
      [catalogId]: (p[catalogId] ?? []).map((t) => t.name === name ? { ...t, enabled: !t.enabled } : t),
    }));
  };

  return (
    <div className="p-6 max-w-3xl mx-auto space-y-4">
      <h1 className="text-xl font-semibold">MCP 数据源设置</h1>
      {error && <div className="text-red-600 text-sm">{error}</div>}
      {catalog.map((item) => {
        const cfg = configOf(item.id);
        const toolList = tools[item.id] ?? [];
        return (
          <div key={item.id} className="border rounded-lg p-4 space-y-2">
            <div className="flex items-center justify-between">
              <div>
                <span className="font-medium">{item.name}</span>
                <span className="ml-2 text-xs text-gray-500">{item.authType}</span>
              </div>
              <span className="text-xs text-gray-400">{cfg ? (cfg.enabled ? "已启用" : "已停用") : "未配置"}</span>
            </div>
            <div className="text-xs text-gray-500 break-all">{item.url}</div>
            {item.authType !== "NONE" && (
              <input
                type="password"
                placeholder="填写你的 Token（保存后不回显）"
                value={tokens[item.id] ?? ""}
                onChange={(e) => setTokens((p) => ({ ...p, [item.id]: e.target.value }))}
                className="w-full border rounded px-2 py-1 text-sm"
              />
            )}
            <div className="flex gap-2">
              <button onClick={() => onTest(item.id)} className="border rounded px-3 py-1 text-sm">测试连接</button>
              <button onClick={() => onSave(item.id, true, toolList.filter((t) => !t.enabled).map((t) => t.name))}
                      className="border rounded px-3 py-1 text-sm">保存</button>
              {cfg && (
                <button onClick={() => onDelete(item.id)} className="border rounded px-3 py-1 text-sm text-red-600">删除</button>
              )}
            </div>
            {testResult[item.id] && <div className="text-xs text-gray-600">{testResult[item.id]}</div>}
            {toolList.length > 0 && (
              <div className="space-y-1">
                {toolList.map((t) => (
                  <label key={t.name} className="flex items-center gap-2 text-sm">
                    <input type="checkbox" checked={t.enabled} onChange={() => toggleTool(item.id, t.name)} />
                    <span className="font-mono text-xs">{t.name}</span>
                    <span className="text-xs text-gray-500">{t.description}</span>
                  </label>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
```

- [ ] **Step 2: 写页面路由**

```tsx
import McpSettingsPage from "@/components/mcp/McpSettingsPage";

export default function Page() {
  return <McpSettingsPage />;
}
```

- [ ] **Step 3: 加导航入口（用户菜单）**

在 `AuthNav.tsx` 的登录态分支新增（与现有「管理」链接并列）：

```tsx
<Link href="/settings/mcp" className={linkClass}>MCP 设置</Link>
```

- [ ] **Step 4: 跑 lint + e2e + 提交**

Run: `cd frontend && pnpm lint && pnpm vitest`
Expected: PASS（无既有用例破坏）。

```bash
git add frontend/components/mcp/McpSettingsPage.tsx frontend/app/settings/mcp/page.tsx \
        frontend/components/auth/AuthNav.tsx
git commit -m "feat(mcp): MCP 设置页与用户菜单入口"
```

---

### Task 3: 冒烟与文档收尾

**Files:**
- Create: `backend/src/integrationTest/.../McpSmokeManual.md`（或脚本留档，如 `backend/scripts/mcp-smoke.sh`）
- Modify: `README.md`（环境变量 `MCP_SECRET_KEY`）
- Modify: `AGENTS.md`（若维护了特性/架构清单）
- Modify: `docs/function/00-功能模块概览.md` 或 `docs/plans/...`（里程碑状态，按 features/README 维护约定）

**Interfaces:**
- Consumes: P0–P3 全部产物。

- [ ] **Step 1: 冒烟真实 MCP（手动，脚本留档）**

用真实妙想/Tushare token 走一遍：配置 → 测试连接 → 对话调用 MCP 工具 → 停用后立即不可用。脚本只留占位 token。

- [ ] **Step 2: 补环境变量文档**

`README.md` 环境变量表新增 `MCP_SECRET_KEY`（base64 32 字节，说明缺失时 MCP Token 加解密不可用）。

- [ ] **Step 3: 更新模块/里程碑文档**

按 `features/README.md` 维护约定，更新功能模块概览与产品落地计划的 mcp-integration 状态。

- [ ] **Step 4: 全量回归 + 提交**

```bash
cd backend && ./gradlew test integrationTest   # JaCoCo ≥80%，ArchUnit 全绿
cd frontend && pnpm lint && pnpm vitest
```

```bash
git add README.md AGENTS.md docs/function/ docs/plans/ backend/scripts/mcp-smoke.sh
git commit -m "docs(mcp): 冒烟留档与文档收尾"
```

---

## P3 完成验证

确认：`/settings/mcp` 可完成「选数据源 → 填 Token → 测试连接 → 工具级开关 → 保存/删除」；未配置 MCP 的对话体验与现状一致；Token 无明文回显；覆盖率与 ArchUnit 不降级。

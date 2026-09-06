# P3 mcp 前端设置页与收尾 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 前端新增 `/settings/mcp`（provider 卡片 + domain 列表 + 工具级开关，无 Token 输入）、`/api/mcp/**` 反代、导航入口；切换 `server-side-memory: true`（前端只发最新一条消息）；冒烟与文档收尾。

**Architecture:** 复用 `lib/http.ts` 的 `request()` + zod、`lib/proxy.ts` 的 `relay()`、`app/settings/mcp/page.tsx` 渲染组件、`AuthNav` 用户菜单入口。

**Tech Stack:** Next.js 15 (App Router) · TypeScript · zod · Tailwind · CopilotKit

**Spec:** `01-requirement/需求规格说明.md`（用户故事 / FR-3/4）、`02-design/设计规格说明.md`（§六/§七）

## Global Constraints

- 前端只经同源 `/api/**` 调后端；响应走 `request()` + zod。
- 无 Token 输入/回显（Token 系统级，前端不碰）。
- 反代复用 `relay()`，`export const dynamic = "force-dynamic"`。

---

### Task 1: API 库 + 反代路由

**Files:**
- Create: `frontend/lib/mcpApi.ts`
- Create: `frontend/app/api/mcp/[...path]/route.ts`

**Interfaces:**
- Consumes: `lib/http.ts` 的 `request`、`lib/proxy.ts` 的 `relay`。
- Produces: `fetchProviders`/`fetchConfigs`/`saveConfig`/`deleteConfig`/`testConnection`/`fetchTools`。

- [ ] **Step 1: 写 API 库**

```ts
import { z } from "zod";
import { request } from "./http";

export const McpProviderSchema = z.object({
  id: z.number(), code: z.string(), name: z.string(),
  authType: z.enum(["NONE", "BEARER", "HEADER"]),
  authHeader: z.string().nullable(), domains: z.array(z.string()),
});
export type McpProviderItem = z.infer<typeof McpProviderSchema>;

export const McpConfigSchema = z.object({
  providerId: z.number(), enabled: z.boolean(),
  disabledTools: z.array(z.string()), configVersion: z.number(),
});
export type McpConfigItem = z.infer<typeof McpConfigSchema>;

export const McpToolSchema = z.object({ name: z.string(), description: z.string(), enabled: z.boolean() });
export type McpToolItem = z.infer<typeof McpToolSchema>;

export const TestResultSchema = z.object({
  success: z.boolean(),
  tools: z.array(z.object({ name: z.string(), description: z.string() })),
  latencyMs: z.number(), errorMessage: z.string().nullable(),
});
export type TestResult = z.infer<typeof TestResultSchema>;

export const fetchProviders = () =>
  request<McpProviderItem[]>("/api/mcp/providers", "GET", undefined, z.array(McpProviderSchema));
export const fetchConfigs = () =>
  request<McpConfigItem[]>("/api/mcp/configs", "GET", undefined, z.array(McpConfigSchema));
export const saveConfig = (providerId: number, body: { enabled?: boolean; disabledTools?: string[] }) =>
  request<McpConfigItem>(`/api/mcp/configs/${providerId}`, "PUT", body, McpConfigSchema);
export const deleteConfig = (providerId: number) => request<void>(`/api/mcp/configs/${providerId}`, "DELETE");
export const testConnection = (providerId: number) =>
  request<TestResult>("/api/mcp/providers/test", "POST", { providerId }, TestResultSchema);
export const fetchTools = (providerId: number) =>
  request<McpToolItem[]>(`/api/mcp/configs/${providerId}/tools`, "GET", undefined, z.array(McpToolSchema));
```

- [ ] **Step 2: 写反代路由**（镜像 journal 的 `[...path]`，base `/api/mcp`）

- [ ] **Step 3: lint + Commit** `feat(mcp): 前端 API 库与反代路由`

---

### Task 2: 设置页组件 + 页面 + 导航入口

**Files:**
- Create: `frontend/components/mcp/McpSettingsPage.tsx`
- Create: `frontend/app/settings/mcp/page.tsx`
- Modify: `frontend/components/auth/AuthNav.tsx`

**Interfaces:**
- Consumes: `lib/mcpApi.ts`（Task 1）、`useAuth`。

- [ ] **Step 1: 写页面组件**（provider 卡片：名称 + domain 标签 + 启用开关 +「测试连接」+ 工具级开关 + 删除；**无 Token 输入**）

- [ ] **Step 2: 写页面路由**（`app/settings/mcp/page.tsx` 渲染 `McpSettingsPage`）

- [ ] **Step 3: 加导航入口**（`AuthNav` 登录态分支加 `<Link href="/settings/mcp">MCP 设置</Link>`）

- [ ] **Step 4: lint + vitest + Commit** `feat(mcp): MCP 设置页与用户菜单入口`

---

### Task 3: 切 server-side-memory + 冒烟 + 文档

**Files:**
- Modify: `backend/src/main/resources/application.yml`（`agentscope.agui.server-side-memory: true`）
- Modify: 前端 CopilotKit 消息发送（只发最新一条，历史由服务端维护）
- Modify: `README.md`（`MCP_SECRET_KEY` 二期用、`MCP_*_TOKEN` seed 说明）
- Create: `backend/scripts/mcp-smoke.sh`（真实妙想/Tushare/Wind 冒烟留档，占位 token）

**Interfaces:**
- Consumes: P0-P3 全部产物。

- [ ] **Step 1: 切服务端历史**——后端 `server-side-memory: true`；前端 CopilotKit 改为只发最新一条消息（对照 CopilotKit 的 message 配置，去掉重发历史）

- [ ] **Step 2: 冒烟**——真实 provider 走一遍：启用 → 测试连接 → 对话调用 MCP 工具 → 停用即失效 → 二次对话记得首轮（harness 会话持久化）

- [ ] **Step 3: 补文档**——`README.md` 环境变量（`MCP_SECRET_KEY`、`MCP_MXDS_TOKEN`/`MCP_TUSHARE_TOKEN`/`MCP_WIND_TOKEN`）；`AGENTS.md` 特性清单

- [ ] **Step 4: 全量回归 + Commit**

```bash
cd backend && ./gradlew test integrationTest   # JaCoCo ≥80%，ArchUnit 全绿
cd frontend && pnpm lint && pnpm vitest
```

```bash
git add README.md AGENTS.md backend/scripts/mcp-smoke.sh backend/src/main/resources/application.yml
git commit -m "docs(mcp): 服务端历史 + 冒烟留档 + 文档收尾"
```

---

## P3 完成验证

确认：`/settings/mcp` 可「选 provider → 启用 → 测试连接 → 工具级开关」；无 Token 输入；未启用 MCP 对话与现状一致；`server-side-memory: true` 后同一 sessionId 二次对话记得首轮；覆盖率与 ArchUnit 不降级。

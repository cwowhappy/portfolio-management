// 管理员 REST 客户端（经 /api/admin 同源反代，权限由后端 ADMIN 角色把关）。
// P3 收敛：私有 request/requestNoContent 已迁 lib/http.ts 的 request()；admin 域传输形态被
// tests/lib/adminApi.test.ts 钉死——全员 credentials:"same-origin"、GET 也带 Content-Type——
// 经 RequestOptions.init 注入（与 http.ts 默认语义的差异仅此一处，见文件头注释）。

import { z } from "zod";
import { request } from "./http";

export const AdminUserViewSchema = z.object({
  id: z.number(),
  username: z.string(),
  role: z.enum(["ADMIN", "USER"]),
  status: z.enum(["PENDING", "APPROVED", "REJECTED"]),
  enabled: z.boolean(),
  email: z.string().nullable(),
});

export type AdminUserView = z.infer<typeof AdminUserViewSchema>;

// —— MS-30 F1：评测与可观测性端点（Task 7 §7.1 契约；fixture 形态与后端切片测试真实响应对齐）——

/** eval 运行历史行（GET /api/admin/eval/runs）；byCategory 值为 [pass, fail, error] 三元组。 */
export const EvalRunSchema = z.object({
  id: z.number(),
  triggeredBy: z.enum(["SCHEDULED", "MANUAL"]),
  status: z.enum(["RUNNING", "COMPLETED", "PARTIAL", "FAILED"]),
  startedAt: z.string(),
  finishedAt: z.string().nullable(), // RUNNING 行收割列为 null
  totalPass: z.number(),
  totalFail: z.number(), // 含 ERROR 的合并口径
  totalError: z.number(),
  byCategory: z.record(z.array(z.number())), // 分类 → [pass, fail, error]
  promptVersions: z.record(z.number()), // 全资产版本快照（asset_key → version）
  questionBankHash: z.string().nullable(), // null = RUNNING/不可比旧档
  alertStatus: z.enum(["NONE", "DEGRADED", "RECOVERED"]),
  baseline: z.boolean(),
  baselineCandidate: z.boolean(),
  verdictReasons: z.array(z.string()),
  durationMs: z.number().nullable(),
  reportPath: z.string().nullable(),
});

export type EvalRun = z.infer<typeof EvalRunSchema>;

/** 手动触发评测受理响应（POST /api/admin/eval/run 202）。 */
export const EvalTriggerResponseSchema = z.object({ runId: z.number() });
export type EvalTriggerResponse = z.infer<typeof EvalTriggerResponseSchema>;

/** 工具调用明细行（GET /api/admin/observability/trace 的 items 元素）；args 为 JSONB 原文串。 */
export const TraceItemSchema = z.object({
  id: z.number(),
  userId: z.number().nullable(),
  conversationId: z.string().nullable(),
  messageId: z.string().nullable(),
  toolName: z.string(),
  args: z.string().nullable(),
  resultText: z.string().nullable(),
  specCount: z.number(),
  asOf: z.string().nullable(),
  asOfKind: z.string().nullable(),
  mcp: z.boolean(),
  failed: z.boolean(),
  durationMs: z.number().nullable(),
  calledAt: z.string(),
});

export type TraceItem = z.infer<typeof TraceItemSchema>;

export const TracePageSchema = z.object({
  items: z.array(TraceItemSchema),
  page: z.number(),
  total: z.number(),
});

export type TracePage = z.infer<typeof TracePageSchema>;

/** trace 明细查询参数（from 含 / to 不含，均为 ISO-8601 时刻；failed 仅 true/false 生效）。 */
export interface TraceQuery {
  page?: number;
  size?: number;
  from?: string;
  to?: string;
  tool?: string;
  failed?: boolean;
}

/** 成本聚合（GET /api/admin/observability/cost）；byDay.date 为上海时区 YYYY-MM-DD。 */
export const CostAggSchema = z.object({
  byDay: z.array(
    z.object({
      date: z.string(),
      promptTokens: z.number(),
      completionTokens: z.number(),
      totalTokens: z.number(),
      turns: z.number(),
    }),
  ),
  byTool: z.array(
    z.object({
      tool: z.string(),
      calls: z.number(),
      avgDurationMs: z.number().nullable(), // null = 该工具全无时长记录
    }),
  ),
});

export type CostAgg = z.infer<typeof CostAggSchema>;

/** 时延聚合（GET /api/admin/observability/latency）；percentile SQL 直出，无数据为 null。 */
export const LatencyAggSchema = z.object({
  turn: z.object({
    p50Ms: z.number().nullable(),
    p95Ms: z.number().nullable(),
    byDay: z.array(
      z.object({
        date: z.string(),
        p50Ms: z.number().nullable(),
        p95Ms: z.number().nullable(),
        turns: z.number(),
      }),
    ),
  }),
  tool: z.object({
    byTool: z.array(
      z.object({
        tool: z.string(),
        p50Ms: z.number().nullable(),
        p95Ms: z.number().nullable(),
        calls: z.number(),
      }),
    ),
  }),
});

export type LatencyAgg = z.infer<typeof LatencyAggSchema>;

/** 提示词资产版本链项（GET /api/admin/prompt-assets）；版本 id 为补注 PUT 的目标键。 */
export const PromptAssetSchema = z.object({
  assetType: z.enum(["SYSTEM_PROMPT", "TOOL_DESC", "SKILL", "INTEL_PROMPT", "EVAL_RUBRIC"]),
  assetKey: z.string(),
  versions: z.array(
    z.object({
      id: z.number(),
      version: z.number(),
      contentHash: z.string(),
      note: z.string().nullable(), // null = 未注记（徽标判据）
      registeredAt: z.string(),
      current: z.boolean(),
    }),
  ),
});

export type PromptAsset = z.infer<typeof PromptAssetSchema>;

export const PromptAssetsSchema = z.object({ assets: z.array(PromptAssetSchema) });
export type PromptAssets = z.infer<typeof PromptAssetsSchema>;

/**
 * 单价显示通道（设计规格 §7.2 / Task 7 传导裁定）：unit-price-cny-per-mtok 无后端 GET，
 * 取构建期 env（元 / Mtok）前端本地乘法估算；未配置或非法值按 0 即不显示。
 */
export const UNIT_PRICE_CNY_PER_MTOK =
  Number(process.env.NEXT_PUBLIC_UNIT_PRICE_CNY_PER_MTOK ?? 0) || 0;

/** 估算成本（元）：ΣbyDay.totalTokens × 单价 / 1e6；单价未配置（≤0）返回 null 即不显示。 */
export function estimateCostCny(agg: Pick<CostAgg, "byDay">): number | null {
  if (!(UNIT_PRICE_CNY_PER_MTOK > 0)) return null;
  const tokens = agg.byDay.reduce((sum, day) => sum + day.totalTokens, 0);
  return (tokens * UNIT_PRICE_CNY_PER_MTOK) / 1_000_000;
}

/** admin 域统一传输形态（tests/lib/adminApi.test.ts 钉住）：same-origin 凭证 + 全员 JSON 头。 */
const adminInit: RequestInit = {
  credentials: "same-origin",
  headers: { "Content-Type": "application/json" },
};

export const adminApi = {
  list: () => request("/api/admin/users", "GET", undefined, z.array(AdminUserViewSchema), { init: adminInit }),
  approve: (id: number) =>
    request(`/api/admin/users/${id}/approve`, "POST", undefined, AdminUserViewSchema, { init: adminInit }),
  reject: (id: number) =>
    request(`/api/admin/users/${id}/reject`, "POST", undefined, AdminUserViewSchema, { init: adminInit }),
  enable: (id: number) =>
    request(`/api/admin/users/${id}/enable`, "POST", undefined, AdminUserViewSchema, { init: adminInit }),
  disable: (id: number) =>
    request(`/api/admin/users/${id}/disable`, "POST", undefined, AdminUserViewSchema, { init: adminInit }),
  resetPassword: (id: number, newPassword: string) =>
    request(`/api/admin/users/${id}/reset-password`, "POST", { newPassword }, AdminUserViewSchema, {
      init: adminInit,
    }),
  setEmail: (id: number, email: string) =>
    request(`/api/admin/users/${id}/email`, "POST", { email }, AdminUserViewSchema, { init: adminInit }),
  /** P1-10：设置/更换 provider token；明文仅在请求体一次经过，响应 204 无回显。 */
  setMcpProviderToken: (code: string, token: string) =>
    request<void>(`/api/admin/mcp/providers/${code}/token`, "PUT", { token }, undefined, { init: adminInit }),
  // —— MS-30 F1：评测与可观测性 ——
  /** 运行历史倒序（裸数组，沿 UserAdminController 先例非信封；RUNNING 行原样返回供轮询）。 */
  listEvalRuns: (limit = 20) =>
    request(`/api/admin/eval/runs?limit=${limit}`, "GET", undefined, z.array(EvalRunSchema), { init: adminInit }),
  /** 手动触发评测：202 受理即返回；进行中 409 / 未启用 503 由 message 通道透出文案。 */
  triggerEvalRun: () =>
    request("/api/admin/eval/run", "POST", undefined, EvalTriggerResponseSchema, { init: adminInit }),
  /** baseline 置位：置 true 仅限 COMPLETED 且非 DEGRADED 跑（422 由 message 通道透出资格文案）。 */
  setEvalBaseline: (id: number, baseline: boolean) =>
    request<void>(`/api/admin/eval/runs/${id}/baseline`, "PUT", { baseline }, undefined, { init: adminInit }),
  /** 版本变更说明补注：{id} 为版本行主键（非资产 key）。 */
  setPromptAssetNote: (id: number, note: string) =>
    request<void>(`/api/admin/prompt-assets/${id}/note`, "PUT", { note }, undefined, { init: adminInit }),
  /** 工具调用明细分页筛选（from 含 / to 不含）。 */
  fetchTrace: (query: TraceQuery = {}) => {
    const params = new URLSearchParams();
    if (query.page != null) params.set("page", String(query.page));
    if (query.size != null) params.set("size", String(query.size));
    if (query.from != null) params.set("from", query.from);
    if (query.to != null) params.set("to", query.to);
    if (query.tool != null) params.set("tool", query.tool);
    if (query.failed != null) params.set("failed", String(query.failed));
    const qs = params.toString();
    return request(`/api/admin/observability/trace${qs ? `?${qs}` : ""}`, "GET", undefined, TracePageSchema, {
      init: adminInit,
    });
  },
  /** 成本看板：按日 token 消耗 + 按工具调用统计。 */
  fetchCost: (days = 7) =>
    request(`/api/admin/observability/cost?days=${days}`, "GET", undefined, CostAggSchema, { init: adminInit }),
  /** 时延看板：轮整体/按日 + 按工具 p50/p95 百分位。 */
  fetchLatency: (days = 7) =>
    request(`/api/admin/observability/latency?days=${days}`, "GET", undefined, LatencyAggSchema, { init: adminInit }),
  /** 提示词版本链（分组、组内倒序、最新 current=true）。 */
  fetchPromptAssets: () => request("/api/admin/prompt-assets", "GET", undefined, PromptAssetsSchema, { init: adminInit }),
};

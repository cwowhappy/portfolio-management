import { z } from "zod";
import { request } from "./http";
import {
  ReportDetailViewSchema,
  ReportSummarySchema,
  ReportTaskStatusSchema,
  StartReportTaskResultSchema,
} from "./reportSchemas";
import type { ReportDetailView, ReportSummary, ReportTaskStatus, StartReportTaskResult } from "./reportSchemas";

// 研报域 api 层（MS-32 深度研究工作流）：五端点逐字对齐后端 ReportTaskController，
// 同源反代走既有 /api/research/[...path] catch-all（GET/POST 均已透传，无需改反代）。
//
// 错误面（http.request 非 2xx 行为——读 lib/http.ts 确认）：只透传错误体 message 字段
// （new Error(message)），code/remaining 均不在错误面上：
// - 402 配额：catch 到 Error("当日研报 token 配额已用尽，次日恢复")。错误体 remaining
//   经 handler 钳 0 后恒为 0（B13），无信息量——正常余量唯一来源是 202 响应的
//   quotaRemaining；若未来 UI 需结构化 code/remaining，须扩展 http.ts 抛带 body 的错误
//   类型，调用方 catch 后取 body.remaining，本层不改签名即可承接。
// - 409 活跃任务（TASK_ACTIVE_EXISTS）/ 非待确认态（REPORT_INVALID_STATE）：同只透传 message。

/** 发起深度研报任务（202 异步）：配额不足 402 / 已有活跃任务 409 / 项目不存在 404。 */
export const startReportTask = (projectId: number) =>
  request<StartReportTaskResult>(
    `/api/research/projects/${projectId}/report-tasks`,
    "POST",
    undefined,
    StartReportTaskResultSchema,
  );

/** 轮询最新任务状态（轮询节奏由调用方控制）：无任务/非本人项目 → 404（catch message 判空态）。 */
export const fetchLatestTask = (projectId: number) =>
  request<ReportTaskStatus>(
    `/api/research/projects/${projectId}/report-tasks/latest`,
    "GET",
    undefined,
    ReportTaskStatusSchema,
  );

/** 项目研报摘要列表（createdAt 倒序）。 */
export const fetchReports = (projectId: number) =>
  request<ReportSummary[]>(
    `/api/research/projects/${projectId}/reports`,
    "GET",
    undefined,
    z.array(ReportSummarySchema),
  );

/** 研报详情：锚点 state/currentValue 为打开时重查瞬态（TTL 缓存内可能非最新重查）。 */
export const fetchReportDetail = (reportId: number) =>
  request<ReportDetailView>(`/api/research/reports/${reportId}`, "GET", undefined, ReportDetailViewSchema);

/** 确认动作：CONFIRM 定稿 / REJECT 拒绝（后端 reason 必填，域校验先于状态检查）。 */
export type ConfirmAction = "CONFIRM" | "REJECT";

/** 确认定稿/拒绝（幂等语义在后端）：REJECT 带 reason；非待确认态 409、REJECT 缺 reason 400。 */
export const confirmReport = (reportId: number, action: ConfirmAction, reason?: string) => {
  const payload = reason === undefined ? { action } : { action, reason };
  return request<ReportSummary>(
    `/api/research/reports/${reportId}/confirm`,
    "POST",
    payload,
    ReportSummarySchema,
  );
};

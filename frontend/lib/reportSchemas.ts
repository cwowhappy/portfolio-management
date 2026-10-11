import { z } from "zod";

// 研报域 zod 镜像（MS-32 深度研究工作流），逐字段对齐后端 ResearchViews 三 record
// （TaskStatusView / ReportSummaryView / ReportDetailView）与域枚举
// ReportTask.Status/Stage、ResearchReport.Status、ReportStalenessService.AnchorState。
// fixture 抄录自 B13 MockMvc 真实响应（BOOT_LIKE_MAPPER，ISO 串形态）——禁止自造（经验 #9）。
// REST JSON 不剥 null：可空字段显式 .nullable()，主键/枚举等必填字段一律不接受 null。

/** 任务四态（ReportTask.Status）：PENDING→RUNNING→SUCCEEDED/FAILED（§3.2 状态机）。 */
export const ReportTaskStateSchema = z.enum(["PENDING", "RUNNING", "SUCCEEDED", "FAILED"]);
/** 任务四阶段（ReportTask.Stage）：COLLECTING→ANALYZING→DRAFTING→VALIDATING（RUNNING 内可推进/回退）。 */
export const ReportStageSchema = z.enum(["COLLECTING", "ANALYZING", "DRAFTING", "VALIDATING"]);
/** 研报三态（ResearchReport.Status，§3.10 确认状态机）：PENDING_CONFIRM→CONFIRMED/REJECTED。 */
export const ReportStatusSchema = z.enum(["PENDING_CONFIRM", "CONFIRMED", "REJECTED"]);
/**
 * 锚点重查四态（F02 假设徽标）：FRESH 现值再现 / STALE 数据时点超线 / CHANGED 现值漂移 /
 * UNVERIFIABLE 不可重查。state/currentValue 为打开详情时重查的瞬态结果（不落库）。
 */
export const AnchorStateSchema = z.enum(["FRESH", "STALE", "CHANGED", "UNVERIFIABLE"]);

/** 发起响应（202）：quotaRemaining 为发起后当日余量（正常余量的唯一来源）。 */
export const StartReportTaskResultSchema = z.object({
  taskId: z.number(),
  quotaRemaining: z.number(),
});

/**
 * 任务轮询视图（GET report-tasks/latest）：FAILED 透出 errorCode/errorMessage 诊断；
 * SUCCEEDED 回连 reportId 供跳转详情，其余态 null；tokenUsage 为累计消耗。
 */
export const ReportTaskStatusSchema = z.object({
  status: ReportTaskStateSchema,
  stage: ReportStageSchema,
  tokenUsage: z.number(),
  errorCode: z.string().nullable(),
  errorMessage: z.string().nullable(),
  reportId: z.number().nullable(),
});

/** 研报摘要视图（列表与确认响应共用，createdAt 倒序）。 */
export const ReportSummarySchema = z.object({
  id: z.number(),
  title: z.string(),
  status: ReportStatusSchema,
  createdAt: z.string(),
});

/**
 * 锚点视图（F02 §3.8）：tool/args/asOf 为锚定真相（落库），state/currentValue 为
 * 打开时重查的瞬态四态；currentValue 不可得为 null（字符串现值，非数字）。
 */
export const ReportAnchorViewSchema = z.object({
  tool: z.string(),
  args: z.record(z.string(), z.unknown()),
  asOf: z.string().nullable(),
  state: AnchorStateSchema,
  currentValue: z.string().nullable(),
});

/** 假设条目：statement 为定稿文本；anchor 为 null 表示未绑定假设（防御性兜底）。 */
export const ReportAssumptionViewSchema = z.object({
  seq: z.number(),
  statement: z.string(),
  anchor: ReportAnchorViewSchema.nullable(),
});

/**
 * 研报详情视图：stalenessCheckedAt 为本次取数时刻（TTL 缓存命中时实际重查最早可追溯至
 * recheck-ttl 前）；limitations 可空（域 create 允许 null）。
 */
export const ReportDetailViewSchema = z.object({
  id: z.number(),
  projectId: z.number(),
  title: z.string(),
  status: ReportStatusSchema,
  contentMd: z.string(),
  limitations: z.string().nullable(),
  createdAt: z.string(),
  stalenessCheckedAt: z.string(),
  assumptions: z.array(ReportAssumptionViewSchema),
});

/** 任务四阶段中文文案（RUNNING 进度展示，§4.1 TaskProgress）。 */
export const REPORT_STAGE_LABELS: Record<ReportStage, string> = {
  COLLECTING: "数据收集中",
  ANALYZING: "分析中",
  DRAFTING: "草稿撰写中",
  VALIDATING: "校验中",
};

/** 研报三态中文 chip（列表/确认卡终态展示，§4.1）。 */
export const REPORT_STATUS_LABELS: Record<ReportStatus, string> = {
  PENDING_CONFIRM: "待确认",
  CONFIRMED: "已定稿",
  REJECTED: "已拒绝",
};

export type ReportTaskState = z.infer<typeof ReportTaskStateSchema>;
export type ReportStage = z.infer<typeof ReportStageSchema>;
export type ReportStatus = z.infer<typeof ReportStatusSchema>;
export type AnchorState = z.infer<typeof AnchorStateSchema>;
export type StartReportTaskResult = z.infer<typeof StartReportTaskResultSchema>;
export type ReportTaskStatus = z.infer<typeof ReportTaskStatusSchema>;
export type ReportSummary = z.infer<typeof ReportSummarySchema>;
export type ReportAnchorView = z.infer<typeof ReportAnchorViewSchema>;
export type ReportAssumptionView = z.infer<typeof ReportAssumptionViewSchema>;
export type ReportDetailView = z.infer<typeof ReportDetailViewSchema>;

import { z } from "zod";

// 研究项目域 zod 镜像（invest-sop P2），逐字段对齐后端 ResearchViews 五 record 与
// StageCompletionService.ManualState。REST JSON 不剥 null（AG-UI NON_NULL 剥离仅作用于
// 事件流），可空字段显式 .nullable()，主键/枚举等必填字段一律不接受 null——
// wire 契约破坏在 api 边界报「数据格式异常」而非深渲染崩溃。

export const ResearchStageSchema = z.enum(["NEW_ANALYSIS", "STRATEGY", "POSITION", "REVIEW"]);
export const ProjectStatusSchema = z.enum(["ACTIVE", "ARCHIVED"]);
/** 三态（D22）：未开始 / 进行中 / 完成——不做百分比。 */
export const StageStatusSchema = z.enum(["NOT_STARTED", "IN_PROGRESS", "COMPLETED"]);
/** 完成方式依据（D22 角标）：PENDING=未完成（无角标），AUTO=产物齐套，MANUAL=手动兜底。 */
export const CompletionBasisSchema = z.enum(["PENDING", "AUTO", "MANUAL"]);
export const StrategyStateSchema = z.enum(["DRAFT", "FINALIZED"]);
export const FalsifierKindSchema = z.enum(["PREDICATE", "EVENT"]);
export const FalsifierPredicateSchema = z.enum(["PRICE_BELOW", "PRICE_ABOVE", "PE_ABOVE", "PB_ABOVE"]);
/** PATCH manualMarks 手动覆盖值（StageCompletionService.ManualState；NULL=清除覆盖不入参）。 */
export const ManualStateSchema = z.enum(["COMPLETED", "REOPENED"]);

export const StageCompletionSchema = z.object({
  stage: ResearchStageSchema,
  status: StageStatusSchema,
  basis: CompletionBasisSchema,
});

export const ProjectViewSchema = z.object({
  id: z.number(),
  stockCode: z.string(),
  stockName: z.string(),
  industryCode: z.string().nullable(),
  title: z.string(),
  currentStage: ResearchStageSchema,
  status: ProjectStatusSchema,
  createdAt: z.string(),
  updatedAt: z.string(),
});

export const StrategyViewSchema = z.object({
  id: z.number(),
  state: StrategyStateSchema,
  thesis: z.string().nullable(),
  valuationLow: z.number().nullable(),
  valuationHigh: z.number().nullable(),
  positionPlan: z.string().nullable(),
  buyConditions: z.string().nullable(),
  riskNotes: z.string().nullable(),
  finalizedAt: z.string().nullable(),
  updatedAt: z.string(),
});

export const FalsifierViewSchema = z.object({
  id: z.number(),
  kind: FalsifierKindSchema,
  predicate: FalsifierPredicateSchema.nullable(),
  threshold: z.number().nullable(),
  eventChecked: z.boolean(),
  note: z.string().nullable(),
  enabled: z.boolean(),
});

/** 详情读模型：完成度为后端唯一计算点产出（S6/NFR-1，前端不重复实现）；strategy=null 表示尚未建草稿。 */
export const ProjectDetailViewSchema = z.object({
  project: ProjectViewSchema,
  completions: z.record(ResearchStageSchema, StageCompletionSchema),
  strategy: StrategyViewSchema.nullable(),
  falsifiers: z.array(FalsifierViewSchema),
});

export type ResearchStage = z.infer<typeof ResearchStageSchema>;
export type ProjectStatus = z.infer<typeof ProjectStatusSchema>;
export type StageStatus = z.infer<typeof StageStatusSchema>;
export type CompletionBasis = z.infer<typeof CompletionBasisSchema>;
export type StrategyState = z.infer<typeof StrategyStateSchema>;
export type FalsifierKind = z.infer<typeof FalsifierKindSchema>;
export type FalsifierPredicate = z.infer<typeof FalsifierPredicateSchema>;
export type ManualState = z.infer<typeof ManualStateSchema>;
export type StageCompletion = z.infer<typeof StageCompletionSchema>;
export type ProjectView = z.infer<typeof ProjectViewSchema>;
export type StrategyView = z.infer<typeof StrategyViewSchema>;
export type FalsifierView = z.infer<typeof FalsifierViewSchema>;
export type ProjectDetailView = z.infer<typeof ProjectDetailViewSchema>;

/** 四阶段声明序（与后端 ResearchStage 枚举一致，进度条/过滤/选择器共用）。 */
export const RESEARCH_STAGES: readonly ResearchStage[] = ["NEW_ANALYSIS", "STRATEGY", "POSITION", "REVIEW"];

// 中文标签与后端各枚举 label() 对齐
export const RESEARCH_STAGE_LABELS: Record<ResearchStage, string> = {
  NEW_ANALYSIS: "新分析",
  STRATEGY: "制定投资策略",
  POSITION: "建仓与持仓",
  REVIEW: "复盘",
};
export const PROJECT_STATUS_LABELS: Record<ProjectStatus, string> = {
  ACTIVE: "研究中",
  ARCHIVED: "已归档",
};
export const STAGE_STATUS_LABELS: Record<StageStatus, string> = {
  NOT_STARTED: "未开始",
  IN_PROGRESS: "进行中",
  COMPLETED: "完成",
};
export const COMPLETION_BASIS_LABELS: Record<CompletionBasis, string> = {
  PENDING: "待定",
  AUTO: "自动",
  MANUAL: "手动",
};
export const STRATEGY_STATE_LABELS: Record<StrategyState, string> = {
  DRAFT: "草稿",
  FINALIZED: "已定稿",
};
export const FALSIFIER_KIND_LABELS: Record<FalsifierKind, string> = {
  PREDICATE: "谓词",
  EVENT: "事件",
};
export const FALSIFIER_PREDICATE_LABELS: Record<FalsifierPredicate, string> = {
  PRICE_BELOW: "价格跌破",
  PRICE_ABOVE: "价格升破",
  PE_ABOVE: "PE 高于",
  PB_ABOVE: "PB 高于",
};
export const MANUAL_STATE_LABELS: Record<ManualState, string> = {
  COMPLETED: "手动完成",
  REOPENED: "已重开",
};

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
// P3 契约枚举（与后端 research 域枚举逐字一致）
/** 检查类型（CheckType）：BUY/ADD 从建仓计划批次发起，SELL/REDUCE 从卖出意图发起（D18）。 */
export const CheckTypeSchema = z.enum(["BUY", "ADD", "REDUCE", "SELL"]);
/** 检查单结论（CheckResult）：CONFIRMED 逐项确认 / OVERRIDDEN 越过命中项（必填理由）。 */
export const CheckResultSchema = z.enum(["CONFIRMED", "OVERRIDDEN"]);
/** 检查项三态（CheckOutcome，D5 软提醒）：UNSET 中性——未设定规则或证伪条件待核对。 */
export const CheckOutcomeSchema = z.enum(["PASS", "HIT", "UNSET"]);

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

// —— P3：建仓计划 / 纪律检查 / 证伪命中（逐字段对齐后端 ResearchViews 三 record + CheckItemResult）——

/** 单条检查项（preview 产物与 submit 快照同构回传）：规则项含 threshold/currentValue，F01/证伪核对项两值为 null。 */
export const CheckItemResultSchema = z.object({
  metric: z.string(),
  threshold: z.number().nullable(),
  currentValue: z.number().nullable(),
  outcome: CheckOutcomeSchema,
});

/** 建仓批次视图（research_entry_batch 行）：amount 可空（金额允许暂缺）。 */
export const EntryBatchViewSchema = z.object({
  seq: z.number(),
  priceLow: z.number(),
  priceHigh: z.number(),
  quantity: z.number(),
  amount: z.number().nullable(),
  ratio: z.number(),
});

/** 建仓计划视图：kellyRatio 为后端读时算得（D23 只做算术），参数缺 → null。 */
export const EntryPlanViewSchema = z.object({
  id: z.number(),
  winRate: z.number().nullable(),
  payoffRatio: z.number().nullable(),
  kellyRatio: z.number().nullable(),
  batches: z.array(EntryBatchViewSchema),
  createdAt: z.string(),
  updatedAt: z.string(),
});

/** 检查留痕视图（append-only，items 为提交时快照）。 */
export const CheckRecordViewSchema = z.object({
  id: z.number(),
  checkType: CheckTypeSchema,
  items: z.array(CheckItemResultSchema),
  result: CheckResultSchema,
  overrideReason: z.string().nullable(),
  createdAt: z.string(),
});

/**
 * 证伪命中合并视图行（D21）：realtime=true 实时求值（不落库，hitAt=null）；
 * realtime=false 历史 hit 留痕行（id/hitAt 为落库标识）。EVENT 条目 hit 恒 false——
 * 展示按 basis（已确认事件/待人工勾选）或 eventChecked，不得单读 hit（Ruling-18）；
 * 历史行 hit=false 自述误导——展示按 realtime 标志给「历史命中」标签。历史行在条件已被
 * 整替删除时现态字段（kind/predicate/threshold/note）为 null，仅保 basis 与 falsifierId。
 */
export const FalsifierHitViewSchema = z.object({
  id: z.number().nullable(),
  falsifierId: z.number(),
  kind: FalsifierKindSchema.nullable(),
  predicate: FalsifierPredicateSchema.nullable(),
  threshold: z.number().nullable(),
  note: z.string().nullable(),
  eventChecked: z.boolean(),
  hit: z.boolean(),
  pending: z.boolean(),
  skipped: z.boolean(),
  basis: z.string().nullable(),
  realtime: z.boolean(),
  hitAt: z.string().nullable(),
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
export type CheckType = z.infer<typeof CheckTypeSchema>;
export type CheckResult = z.infer<typeof CheckResultSchema>;
export type CheckOutcome = z.infer<typeof CheckOutcomeSchema>;
export type CheckItemResult = z.infer<typeof CheckItemResultSchema>;
export type EntryBatchView = z.infer<typeof EntryBatchViewSchema>;
export type EntryPlanView = z.infer<typeof EntryPlanViewSchema>;
export type CheckRecordView = z.infer<typeof CheckRecordViewSchema>;
export type FalsifierHitView = z.infer<typeof FalsifierHitViewSchema>;

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
// P3 中文标签（与后端 CheckType/CheckResult/CheckOutcome label() 对齐）
export const CHECK_TYPE_LABELS: Record<CheckType, string> = {
  BUY: "买入",
  ADD: "加仓",
  REDUCE: "减仓",
  SELL: "卖出",
};
export const CHECK_RESULT_LABELS: Record<CheckResult, string> = {
  CONFIRMED: "确认",
  OVERRIDDEN: "越过",
};
export const CHECK_OUTCOME_LABELS: Record<CheckOutcome, string> = {
  PASS: "通过",
  HIT: "命中",
  UNSET: "未设定",
};
/** 规则类检查项 metric 枚举名 → 展示名（与后端 DisciplineCheckService 四常量逐字一致）。 */
export const CHECK_METRIC_LABELS: Record<string, string> = {
  SINGLE_POSITION_RATIO: "计划后单票占比",
  INDUSTRY_POSITION_RATIO: "计划后行业占比",
  STOCK_PE_MAX: "当前 PE",
  STOCK_PB_MAX: "当前 PB",
};
/**
 * F01 必查项（与后端 DisciplineCheckService.F01_* 常量逐字对齐）：preview 勾选键与
 * 确认卡勾选框共用——勾选=true 记 PASS，未勾选记 HIT（布尔语境，后端转换）。
 */
export const F01_MUST_ITEMS: readonly string[] = ["能力圈", "安全边际", "估值核对", "买入条件"];
